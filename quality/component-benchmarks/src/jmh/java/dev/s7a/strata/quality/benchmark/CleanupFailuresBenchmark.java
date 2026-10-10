package dev.s7a.strata.quality.benchmark;

import dev.s7a.strata.runtime.FailureAccumulator;
import dev.s7a.strata.runtime.minecraft.canvas.CanvasFailures;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * Calls the actual JVM collectors directly through their compiled internal boundary.
 * Every invocation owns a fresh exception graph and borrows one synchronous callback.
 * Java accesses the JVM-public internal classes without reflection or changing Kotlin visibility.
 */
public class CleanupFailuresBenchmark {
    /** Returns the exact collected failure after every independent callback has run. */
    @Benchmark
    public Throwable cleanup(Cleanup state) {
        return state.execute();
    }

    /** Runs every compiled success and failure control outside timing. */
    public static void verifyWork() {
        for (Collector collector : Collector.values()) {
            for (Workload workload : Workload.values()) {
                Cleanup state = new Cleanup();
                state.collector = collector;
                state.workload = workload;
                for (int run = 0; run < 128; run += 1) {
                    state.execute();
                    state.verifyResult();
                }
                System.out.println("cleanup," + collector + "," + workload + ",callbacks=" + state.completed
                        + ",observedIdentities=" + state.distinctObserved());
            }
        }
    }

    /** The existing collectors preserve separate suppressed-graph contracts. */
    public enum Collector {
        /** Retained-engine cleanup flattens optional later failures. */
        Core,
        /** Device cleanup remembers nested graphs without flattening them. */
        Canvas
    }

    /** Fixed callback counts and exception shapes, independent of timing settings. */
    public enum Workload {
        /** No cleanup owner. */
        CleanEmpty(0),
        /** One successful owner. */
        CleanOne(1),
        /** A successful fan-out. */
        CleanMany(64),
        /** One failure followed by successful owners. */
        Single(64),
        /** Three ordinary exceptions with different identities. */
        Distinct(64),
        /** One exception thrown three times. */
        Identical(64),
        /** Two distinct exceptions whose value equality returns true. */
        EqualDistinct(64),
        /** A later error carries two nested errors which callbacks also throw. */
        Nested(64),
        /** A later suppressed graph contains a reference cycle. */
        Cyclic(64),
        /** An initial cyclic graph precedes repeated and new failures. */
        Initial(64);

        private final int callbacks;

        Workload(int callbacks) {
            this.callbacks = callbacks;
        }
    }

    /** Retains only the current operation's graph and one reusable borrowed callback. */
    @State(Scope.Thread)
    public static class Cleanup {
        /** Actual collector selected by JMH. */
        @Param
        public Collector collector = Collector.Core;
        /** Complete success or failure shape selected by JMH. */
        @Param
        public Workload workload = Workload.CleanEmpty;

        private static final Throwable[] EMPTY = new Throwable[0];
        private Throwable[] failures = EMPTY;
        private Throwable initial;
        private Throwable current;
        private Throwable result;
        private int completed;
        private final Function0<Unit> action = () -> {
            completed += 1;
            if (current != null) throw (RuntimeException) current;
            return Unit.INSTANCE;
        };

        /** Attempts every owner, catching only the collector's final rethrow. */
        public Throwable execute() {
            prepare();
            completed = 0;
            result = null;
            if (collector == Collector.Core) {
                FailureAccumulator accumulator = new FailureAccumulator(initial);
                for (int index = 0; index < workload.callbacks; index += 1) {
                    current = index < failures.length ? failures[index] : null;
                    accumulator.capture(action);
                }
                current = null;
                try {
                    accumulator.throwIfPresent();
                } catch (Throwable failure) {
                    result = failure;
                }
            } else {
                CanvasFailures accumulator = new CanvasFailures(initial);
                for (int index = 0; index < workload.callbacks; index += 1) {
                    current = index < failures.length ? failures[index] : null;
                    accumulator.attempt(action);
                }
                current = null;
                try {
                    accumulator.throwIfPresent();
                } catch (Throwable failure) {
                    result = failure;
                }
            }
            return result;
        }

        private void prepare() {
            initial = null;
            switch (workload) {
                case CleanEmpty, CleanOne, CleanMany -> failures = EMPTY;
                case Single -> failures = new Throwable[]{new Failure(false)};
                case Distinct -> failures = new Throwable[]{new Failure(false), new Failure(false), new Failure(false)};
                case Identical -> {
                    Throwable first = new Failure(false);
                    failures = new Throwable[]{first, first, first};
                }
                case EqualDistinct -> failures = new Throwable[]{new Failure(true), new Failure(true)};
                case Nested -> {
                    failures = new Throwable[]{new Failure(false), new Failure(false), new Failure(false), new Failure(false)};
                    failures[1].addSuppressed(failures[2]);
                    failures[2].addSuppressed(failures[3]);
                }
                case Cyclic -> {
                    failures = new Throwable[]{new Failure(false), new Failure(false), new Failure(false)};
                    failures[1].addSuppressed(failures[2]);
                    failures[2].addSuppressed(failures[1]);
                    failures[2].addSuppressed(failures[0]);
                }
                case Initial -> {
                    initial = new Failure(false);
                    Throwable existing = new Failure(false);
                    Throwable later = new Failure(false);
                    initial.addSuppressed(existing);
                    existing.addSuppressed(initial);
                    failures = new Throwable[]{initial, existing, later, later};
                }
            }
        }

        private void verifyResult() {
            require(completed == workload.callbacks, "Cleanup skipped an independent callback");
            if (failures.length == 0) {
                require(result == null, "Successful cleanup recorded a failure");
                return;
            }
            Throwable primary = failures[0];
            require(result == primary, "Primary throwable identity changed");
            Throwable[] expected = switch (workload) {
                case CleanEmpty, CleanOne, CleanMany, Single, Identical -> EMPTY;
                case Distinct -> new Throwable[]{failures[1], failures[2]};
                case EqualDistinct -> new Throwable[]{failures[1]};
                case Nested -> collector == Collector.Core
                        ? new Throwable[]{failures[1], failures[2], failures[3]} : new Throwable[]{failures[1]};
                case Cyclic -> collector == Collector.Core
                        ? new Throwable[]{failures[1], failures[2]} : new Throwable[]{failures[1]};
                case Initial -> new Throwable[]{failures[1], failures[2]};
            };
            Throwable[] actual = primary.getSuppressed();
            require(actual.length == expected.length, "Suppression count changed");
            for (int index = 0; index < expected.length; index += 1) {
                require(actual[index] == expected[index], "Suppression identity or order changed");
            }
            if (workload == Workload.Nested || workload == Workload.Cyclic) {
                require(failures[1].getSuppressed()[0] == failures[2], "Nested graph changed");
            }
        }

        private int distinctObserved() {
            int count = 0;
            for (int index = 0; index < failures.length; index += 1) {
                boolean repeated = false;
                for (int previous = 0; previous < index; previous += 1) {
                    if (failures[previous] == failures[index]) repeated = true;
                }
                if (repeated == false) count += 1;
            }
            return count;
        }
    }

    private static void require(boolean accepted, String message) {
        if (accepted == false) throw new IllegalStateException(message);
    }

    /** Stackless errors isolate bookkeeping from stack capture while retaining suppression and unusual equality. */
    private static class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final boolean equal;

        Failure(boolean equal) {
            super("cleanup fixture failure", null, true, false);
            this.equal = equal;
        }

        @Override
        public boolean equals(Object other) {
            return this == other || (equal && other instanceof Failure failure && failure.equal);
        }

        @Override
        public int hashCode() {
            return equal ? 0 : System.identityHashCode(this);
        }
    }
}
