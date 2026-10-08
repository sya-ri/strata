package dev.s7a.strata.quality.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Measures actual guarded lifetime operations with bounded persistent pending states and explicit complete protocols.
 * Native objects are CPU fakes; these measurements establish no GPU or native frame-rate behavior.
 */
public open class NativeLifetimePollingBenchmark {
    /**
     * Polls one stable actual device state, preserving its selected retained membership across every invocation.
     */
    @Benchmark
    public fun poll(state: Polling): Int = state.workload.poll()

    /**
     * Includes preparation, queued GUI use, consumption and completion for one real reusable generation.
     */
    @Benchmark
    public fun submit(state: Submission): Int = state.workload.submit()

    /**
     * Includes fresh owner construction, admission, pending retirement, signalled cleanup and terminal release.
     * Construction remains inside this boundary rather than hiding its allocations in invocation setup.
     */
    @Benchmark
    public fun retireProtocol(state: Retirement): Int = NativeLifetimePollingWorkload(state.subject, state.count, Condition.InitializationPending).use { it.retireAndSignal() }

    /**
     * Identifies the independently budgeted resource groups retained by one device.
     */
    public enum class Subject {
        Targets,
        Portable,
        Mixed,
    }

    /**
     * Defines a stable pending or settled lifetime state; no measured poll silently drains its membership.
     */
    public enum class Condition {
        Settled,
        InitializationPending,
        GuiPending,
        RetiredPending,
        AsynchronousDestruction,
        Quarantined,
        ReloadPending,
    }

    /**
     * Owns the current persistent device until trial teardown, with one attachment/presenter per admitted record.
     */
    @State(Scope.Thread)
    public open class Polling {
        /**
         * Resource group whose actual lifetime implementation is exercised.
         */
        @JvmField
        @Param("Targets", "Portable", "Mixed")
        public var subject: Subject = Subject.Targets

        /**
         * Actual record count in each selected independent group.
         */
        @JvmField
        @Param("0", "1", "64")
        public var count: Int = 0

        /**
         * Persistent ownership and completion state.
         */
        @JvmField
        @Param("Settled", "InitializationPending", "GuiPending", "RetiredPending", "AsynchronousDestruction", "Quarantined", "ReloadPending")
        public var condition: Condition = Condition.Settled

        internal lateinit var workload: NativeLifetimePollingWorkload

        /**
         * Builds and verifies the actual selected state outside collection.
         */
        @Setup(Level.Trial)
        public fun prepare() {
            workload = NativeLifetimePollingWorkload(subject, count, condition)
            workload.verifyRetained()
        }

        /**
         * Requires successful terminal physical release and no remaining fake handles.
         */
        @TearDown(Level.Trial)
        public fun close() {
            workload.verifyRetained()
            workload.close()
        }
    }

    /**
     * Holds immutable display commands and reusable live owners for complete GUI submissions.
     */
    @State(Scope.Thread)
    public open class Submission {
        /**
         * Resource groups submitted together.
         */
        @JvmField
        @Param("Targets", "Portable", "Mixed")
        public var subject: Subject = Subject.Targets

        /**
         * Current per-group record count.
         */
        @JvmField
        @Param("0", "1", "64")
        public var count: Int = 0

        internal lateinit var workload: NativeLifetimePollingWorkload

        /**
         * Primes live resources and immediate CPU completions before collection.
         */
        @Setup(Level.Trial)
        public fun prepare() {
            workload = NativeLifetimePollingWorkload(subject, count, Condition.Settled)
            workload.immediateCompletions()
            workload.submit()
            workload.verifyRetained()
        }

        /**
         * Releases every admitted owner without retaining historical fake completions.
         */
        @TearDown(Level.Trial)
        public fun close() {
            workload.verifyRetained()
            workload.close()
        }
    }

    /**
     * Keeps only protocol parameters; each measured operation owns and releases its complete fresh device.
     */
    @State(Scope.Thread)
    public open class Retirement {
        /**
         * Resource groups retired together.
         */
        @JvmField
        @Param("Targets", "Portable", "Mixed")
        public var subject: Subject = Subject.Targets

        /**
         * Per-group admitted count before retirement.
         */
        @JvmField
        @Param("0", "1", "64")
        public var count: Int = 0
    }

    /**
     * Shared deterministic fixture admission before any JMH collection begins.
     */
    public companion object {
        /**
         * Verifies persistent membership and terminal release for every native/portable input before timing.
         */
        @JvmStatic
        public fun verifyWork() {
            Subject.entries.forEach { subject ->
                listOf(0, 1, 64).forEach { count ->
                    Condition.entries.forEach { condition ->
                        NativeLifetimePollingWorkload(subject, count, condition).use { workload ->
                            repeat(8) { workload.poll() }
                            workload.verifyRetained()
                        }
                    }
                    NativeLifetimePollingWorkload(subject, count, Condition.Settled).use { workload ->
                        workload.immediateCompletions()
                        repeat(8) { workload.submit() }
                        workload.verifyRetained()
                    }
                    NativeLifetimePollingWorkload(subject, count, Condition.InitializationPending).use { workload ->
                        check(workload.retireAndSignal() == 0)
                    }
                }
            }
        }
    }
}
