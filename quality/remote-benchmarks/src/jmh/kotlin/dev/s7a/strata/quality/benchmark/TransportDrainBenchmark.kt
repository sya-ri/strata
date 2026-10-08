package dev.s7a.strata.quality.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual negotiated service queues without screens, native classes or presentation work.
 * Includes packet preparation, synchronized ingress and every bounded peer tick needed to finish one frozen cycle.
 * The identical reflection adapter supplies logical timestamps; public tick clock/snapshot/failure orchestration is excluded.
 */
public open class TransportDrainBenchmark {
    /**
     * Runs matched transport work through authenticated inbox, consecutive-sequence delivery and outgoing flush.
     */
    @Benchmark
    public fun cycle(state: Fleet): Long = state.cycle()

    /**
     * One JMH worker owns the service; the busy producer owns only immutable ingress snapshots.
     */
    @State(Scope.Thread)
    public open class Fleet {
        /**
         * Number of independently negotiated players, with no retained screen sessions.
         */
        @JvmField
        @Param("1", "100", "1000")
        public var peers: Int = 1

        /**
         * External JMH name decoded once into the frozen typed fixture.
         */
        @JvmField
        @Param("Idle", "One", "Sparse", "Seven", "Eight", "SixtyThree", "ExactLimit", "LimitPlusOne", "SequenceGap", "BusyProducer")
        public var workload: String = "Idle"
        private lateinit var fleet: TransportDrainFleet

        /**
         * Performs real discovery and negotiation outside measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fleet = TransportDrainFleet(peers, TransportDrainWorkload.valueOf(workload))
        }

        /**
         * Runs one cycle without retaining earlier outputs.
         */
        public fun cycle(): Long = fleet.cycle()

        /**
         * Releases service peers, connections and the independent producer.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fleet.close()
        }
    }

    /**
     * Deterministic work checks run automatically through the shared selection hook before timing.
     */
    public companion object {
        /**
         * Checks every peer count and control, ordered bytes, exact delivered counts and terminal release.
         * Both original and early-exit runtimes must pass this same frozen behavioral fixture.
         */
        @JvmStatic
        public fun verifyWork() {
            listOf(1, 100, 1000).forEach { peers ->
                TransportDrainWorkload.entries.forEach { workload ->
                    TransportDrainFleet(peers, workload).use { fleet ->
                        repeat(2) { fleet.cycle() }
                        fleet.verifyDrained()
                    }
                    println("Transport drain $peers $workload: ordered input/output, bounded ticks and terminal release")
                }
            }
        }
    }
}
