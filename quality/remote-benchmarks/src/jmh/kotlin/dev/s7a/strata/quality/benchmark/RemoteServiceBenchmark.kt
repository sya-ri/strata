package dev.s7a.strata.quality.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete native-free negotiated service work, including actual public ownership and bounded protocol output.
 * Steady ticks, individual/all publications, distinct-peer churn and complete lifecycles remain separate units.
 */
public open class RemoteServiceBenchmark {
    /**
     * One complete negotiated tick without membership or declaration changes.
     */
    @Benchmark
    public fun idle(state: Steady): Int = state.fleet.idle()

    /**
     * One actual current source publication, one complete tick and all resulting output processing.
     */
    @Benchmark
    public fun oneSource(state: Changed): Int = state.fleet.oneSource()

    /**
     * Every current source publication, all fixed delivery ticks and every resulting output message.
     */
    @Benchmark
    public fun allSources(state: Changed): Int = state.fleet.allSources()

    /**
     * A distinct peer's five-phase join/open/applied/disconnect lifetime with fixed bounded delivery ticks.
     */
    @Benchmark
    public fun churn(state: Churn): Int = state.fleet.churn()

    /**
     * Construction through six fixed phases and terminal release; bounded delivery expands to two/three ticks.
     */
    @Benchmark
    public fun lifecycle(state: Lifecycle): Int =
        RemoteServiceFleet(state.topology).use { fleet ->
            fleet.establish()
            fleet.finishLifecycle()
        }

    /**
     * All 25 stable topologies, primed outside the measured operation.
     */
    @State(Scope.Thread)
    public open class Steady {
        /**
         * Generated typed initial fleet topology.
         */
        @JvmField
        @Param
        public var topology: RemoteServiceTopology = RemoteServiceTopology.Empty
        internal lateinit var fleet: RemoteServiceFleet

        /**
         * Establishes current negotiated and applied owners before collection.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fleet = RemoteServiceFleet(topology)
            fleet.establish()
        }

        /**
         * Releases the current owner fleet on the same worker.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fleet.close()
    }

    /**
     * The 21 real-source topologies; source-free controls are represented by stable/lifecycle operations.
     */
    @State(Scope.Thread)
    public open class Changed {
        /**
         * Nonempty generated source topology, decoded by JMH at its adapter boundary.
         */
        @JvmField
        @Param("Peers1Huds0Screens1", "Peers1Huds1Screens0", "Peers1Huds1Screens1", "Peers1Huds4Screens0", "Peers1Huds4Screens1", "Peers1Huds16Screens0", "Peers1Huds16Screens1", "Peers16Huds0Screens1", "Peers16Huds1Screens0", "Peers16Huds1Screens1", "Peers16Huds4Screens0", "Peers16Huds4Screens1", "Peers16Huds16Screens0", "Peers16Huds16Screens1", "Peers128Huds0Screens1", "Peers128Huds1Screens0", "Peers128Huds1Screens1", "Peers128Huds4Screens0", "Peers128Huds4Screens1", "Peers128Huds16Screens0", "Peers128Huds16Screens1")
        public var topology: RemoteServiceTopology = RemoteServiceTopology.Peers1Huds0Screens1
        internal lateinit var fleet: RemoteServiceFleet

        /**
         * Establishes current negotiated and applied owners before publication collection.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fleet = RemoteServiceFleet(topology)
            fleet.establish()
        }

        /**
         * Releases every source observation and protocol owner.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fleet.close()
    }

    /**
     * The 24 positive initial populations; an additional distinct peer is included inside each operation.
     */
    @State(Scope.Thread)
    public open class Churn {
        /**
         * Initial population; peak population is one larger.
         */
        @JvmField
        @Param("Peers1Huds0Screens0", "Peers1Huds0Screens1", "Peers1Huds1Screens0", "Peers1Huds1Screens1", "Peers1Huds4Screens0", "Peers1Huds4Screens1", "Peers1Huds16Screens0", "Peers1Huds16Screens1", "Peers16Huds0Screens0", "Peers16Huds0Screens1", "Peers16Huds1Screens0", "Peers16Huds1Screens1", "Peers16Huds4Screens0", "Peers16Huds4Screens1", "Peers16Huds16Screens0", "Peers16Huds16Screens1", "Peers128Huds0Screens0", "Peers128Huds0Screens1", "Peers128Huds1Screens0", "Peers128Huds1Screens1", "Peers128Huds4Screens0", "Peers128Huds4Screens1", "Peers128Huds16Screens0", "Peers128Huds16Screens1")
        public var topology: RemoteServiceTopology = RemoteServiceTopology.Peers1Huds0Screens0
        internal lateinit var fleet: RemoteServiceFleet

        /**
         * Establishes the original fleet outside the churn operation.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fleet = RemoteServiceFleet(topology)
            fleet.establish()
        }

        /**
         * Releases the surviving initial fleet.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = fleet.close()
    }

    /**
     * Construction/lifetime controls retain all 25 topologies without an extra primed fleet.
     */
    @State(Scope.Thread)
    public open class Lifecycle {
        /**
         * Complete current topology, including the four zero-session controls.
         */
        @JvmField
        @Param
        public var topology: RemoteServiceTopology = RemoteServiceTopology.Empty
    }

    /**
     * Generated inventory and deterministic work admission before timing.
     */
    public companion object {
        /**
         * Verifies every timed case and independent callback/failure/lifetime gate outside JMH operations.
         */
        @JvmStatic
        public fun verifyWork(): Unit = RemoteServiceWorkEvidence.verify()
    }
}
