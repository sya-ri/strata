package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual rejected public admissions, including exception construction and catch cost on both runtime sides.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class TextAreaRejectionBenchmark {
    /**
     * Attempts one rejected cold constructor and returns its original failure to JMH.
     */
    @Benchmark
    public fun construct(input: Inputs): IllegalArgumentException = rejected { TextAreaState(input.raw, input.workload.maximum) }

    /**
     * Performs 64 rejected public writes against one observed state; its old value remains committed.
     */
    @Benchmark
    public fun setter64(input: Inputs): IllegalArgumentException {
        var failure = rejected { input.state.value = input.raw }
        repeat(63) { failure = rejected { input.state.value = input.raw } }
        return failure
    }

    private inline fun rejected(operation: () -> Unit): IllegalArgumentException {
        try {
            operation()
        } catch (failure: IllegalArgumentException) {
            return failure
        }
        error("Rejected fixture was accepted")
    }

    /**
     * Worker-owned raw input and single live observer, prepared before timing.
     */
    @State(Scope.Thread)
    public open class Inputs {
        /**
         * Complete compiled failure inventory.
         */
        @JvmField
        @Param
        public var workload: TextAreaRejectedInput = TextAreaRejectedInput.ControlFirst

        /**
         * Prepared rejected raw input.
         */
        public lateinit var raw: String

        /**
         * Current accepted state whose value/scroll/notifications must survive every rejection.
         */
        public lateinit var state: TextAreaState

        private var release: AutoCloseable? = null

        /**
         * Admission of an observer that fails if any rejected attempt publishes text.
         */
        @Setup(Level.Trial)
        public fun setup() {
            raw = workload.prepare()
            state = TextAreaState("A", workload.maximum)
            release = state.observe { error("Rejected input notified the observer") }
        }

        /**
         * Releases the interval after collection or verifier failure.
         */
        @TearDown(Level.Trial)
        public fun close() {
            release?.close()
            release = null
        }
    }

    /**
     * Independent failure and unchanged-state work admission used by generic discovery.
     */
    public companion object {
        /**
         * Preserves every rejected constructor/batch case and its winning failure.
         */
        @JvmStatic
        public fun verifyWork() {
            val benchmark = TextAreaRejectionBenchmark()
            for (workload in TextAreaRejectedInput.entries) {
                val input = Inputs().also {
                    it.workload = workload
                    it.setup()
                }
                try {
                    val scroll = input.state.scrollState
                    check(benchmark.construct(input).message == workload.failure.message)
                    check(benchmark.setter64(input).message == workload.failure.message)
                    check(input.state.value == "A")
                    check(input.state.scrollState === scroll)
                } finally {
                    input.close()
                }
            }
        }
    }
}
