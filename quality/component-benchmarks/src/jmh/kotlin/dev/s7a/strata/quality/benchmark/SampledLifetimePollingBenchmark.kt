package dev.s7a.strata.quality.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Calls the actual frozen Fabric sampled-image manager through its normal application classloader.
 * Persistent polls and complete cached GUI/retirement protocols have separate measurement boundaries.
 */
public open class SampledLifetimePollingBenchmark {
    /**
     * Polls all retained actual source entries without emptying the selected stable state after its first operation.
     */
    @Benchmark
    public fun poll(state: Polling): Int = state.workload.poll()

    /**
     * Includes actual cache borrow, every queued source, pin release, GUI consumption and nonblocking settlement.
     */
    @Benchmark
    public fun submit(state: Submission): Int = state.workload.submit()

    /**
     * Includes fresh admission and signalled retirement rather than timing subsequent empty polls as retirement work.
     */
    @Benchmark
    public fun retireProtocol(state: Retirement): Int {
        return SampledLifetimePollingWorkload(state.count, NativeLifetimePollingBenchmark.Condition.InitializationPending).use { it.retireAndSignal() }
    }

    /**
     * Holds one actual current sampled manager with one owner up to 256 entries and two owners at 512.
     */
    @State(Scope.Thread)
    public open class Polling {
        /**
         * Total current device entries, with distinct immutable source identity for every entry.
         */
        @JvmField
        @Param("0", "1", "256", "512")
        public var count: Int = 0

        /**
         * Stable source ownership and native completion condition.
         */
        @JvmField
        @Param("Settled", "InitializationPending", "GuiPending", "RetiredPending", "AsynchronousDestruction", "Quarantined", "ReloadPending")
        public var condition: NativeLifetimePollingBenchmark.Condition = NativeLifetimePollingBenchmark.Condition.Settled

        internal lateinit var workload: SampledLifetimePollingWorkload

        /**
         * Resolves the real Fabric archive and constructs the selected bounded owner state before collection.
         */
        @Setup(Level.Trial)
        public fun prepare() {
            workload = SampledLifetimePollingWorkload(count, condition)
            workload.verifyRetained()
        }

        /**
         * Requires the persistent count and terminal physical release of every admitted source.
         */
        @TearDown(Level.Trial)
        public fun close() {
            workload.verifyRetained()
            workload.close()
        }
    }

    /**
     * Owns reusable cached sources and immediate CPU fences for complete submissions.
     */
    @State(Scope.Thread)
    public open class Submission {
        /**
         * Total source count across the independently bounded screen owners.
         */
        @JvmField
        @Param("0", "1", "256", "512")
        public var count: Int = 0

        internal lateinit var workload: SampledLifetimePollingWorkload

        /**
         * Primes every actual cache entry and complete queued submission outside collection.
         */
        @Setup(Level.Trial)
        public fun prepare() {
            workload = SampledLifetimePollingWorkload(count, NativeLifetimePollingBenchmark.Condition.Settled)
            workload.immediateCompletions()
            workload.submit()
            workload.verifyRetained()
        }

        /**
         * Requires unchanged cache admission and successful terminal release.
         */
        @TearDown(Level.Trial)
        public fun close() {
            workload.verifyRetained()
            workload.close()
        }
    }

    /**
     * Supplies only the frozen source-count parameters to each complete fresh measured protocol.
     */
    @State(Scope.Thread)
    public open class Retirement {
        /**
         * Total source count to admit and retire.
         */
        @JvmField
        @Param("0", "1", "256", "512")
        public var count: Int = 0
    }

    companion object {
        /**
         * Verifies actual bounded Fabric source admission, repeated state stability and terminal physical release.
         */
        @JvmStatic
        public fun verifyWork() {
            listOf(0, 1, 256, 512).forEach { count ->
                NativeLifetimePollingBenchmark.Condition.entries.forEach { condition ->
                    SampledLifetimePollingWorkload(count, condition).use { workload ->
                        repeat(8) { workload.poll() }
                        workload.verifyRetained()
                    }
                }
                SampledLifetimePollingWorkload(count, NativeLifetimePollingBenchmark.Condition.Settled).use { workload ->
                    workload.immediateCompletions()
                    repeat(8) { workload.submit() }
                    workload.verifyRetained()
                }
                SampledLifetimePollingWorkload(count, NativeLifetimePollingBenchmark.Condition.InitializationPending).use { workload ->
                    check(workload.retireAndSignal() == 0)
                }
            }
        }
    }
}
