package dev.s7a.strata.quality.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Native-free real retained remote session work, separate from the fixed protocol corpus and real host transport.
 */
public open class RemoteSessionBenchmark {
    /**
     * Projects unchanged retained owners without an outgoing declaration update.
     */
    @Benchmark
    public fun idle(state: Sessions): Int = state.idle()

    /**
     * Publishes one shared revision and projects each owner's real declaration patch.
     */
    @Benchmark
    public fun update(state: Sessions): Int = state.update()

    /**
     * Constructs, attaches, updates and terminates independent owners and their source subscriptions.
     */
    @Benchmark
    public fun lifecycle(state: Sessions): Int = state.lifecycle()

    /**
     * Current JMH-worker-owned fixtures, with bounded current outputs and no retained iteration history.
     */
    @State(Scope.Thread)
    public open class Sessions {
        /**
         * Typed fixture name decoded once from standard JMH parameters.
         */
        @JvmField
        @Param("Single100", "Single8192", "Shared16At512")
        public var workload: String = "Single100"
        private lateinit var selected: RemoteSessionWorkload
        private lateinit var fleet: RemoteSessionFleet

        /**
         * Prepares steady owners outside JMH operations.
         */
        @Setup(Level.Trial)
        public fun setup() {
            selected = RemoteSessionWorkload.valueOf(workload)
            fleet = RemoteSessionFleet(selected)
        }

        /**
         * Executes the unchanged retained operation.
         */
        public fun idle(): Int = fleet.idle()

        /**
         * Executes a real shared revision.
         */
        public fun update(): Int = fleet.update()

        /**
         * Owns independent construction through terminal close inside the lifetime operation.
         */
        public fun lifecycle(): Int = RemoteSessionFleet(selected).use(RemoteSessionFleet::update)

        /**
         * Releases the steady owners on their worker.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fleet.close()
        }
    }
}
