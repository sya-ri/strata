package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Full outgoing invocation lifecycles on actual native connections and actual common-service peers.
 * Immutable logical sources are built before timing; owner setup, negotiation, byte parity and cleanup are included.
 * Supplemental CPU collection separately isolates queue/control, remaining flush and complete transfer intervals.
 */
public open class OutgoingFragmentBenchmark {
    /**
     * Complete connection lifecycle, including ordinary public callback controls.
     */
    @Benchmark
    public fun connectionCycle(state: Transfer): Int = state.cycle()

    /**
     * Complete actual-service lifecycle using the production construction site and original private peer tick.
     */
    @Benchmark
    public fun serverCycle(state: Server): Int = state.cycle()

    /**
     * Frozen logical corpus on the production native path or the ordinary public inner-only callback path.
     */
    @State(Scope.Thread)
    public open class Transfer {
        /**
         * Number of independently confined connections in a complete invocation.
         */
        @JvmField
        @Param("1", "8")
        public var owners: Int = 1

        /**
         * Typed source/control corpus decoded at the JMH boundary.
         */
        @JvmField
        @Param("Hello", "Control", "SmallAction", "OneFragment", "MultiFragment", "OneMiB", "NearLimit", "NegotiatedSmall", "ImmediateMany", "DelayedMany", "UnsentCancellation", "PartialCancellation", "EntryCapacity", "ByteCapacity")
        public var workload: String = "Hello"

        /**
         * Production native output or unchanged ordinary public callback layout.
         */
        @JvmField
        @Param("Production", "Public")
        public var route: String = "Production"
        private lateinit var fixture: OutgoingFragmentFixture

        /**
         * Freezes sources before the full lifecycle interval.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = OutgoingFragmentFixture(owners, OutgoingFragmentWorkload.valueOf(workload), OutgoingFragmentRoute.valueOf(route))
        }

        /**
         * Runs one complete prepared/verified/released actual connection cycle.
         */
        public fun cycle(): Int {
            fixture.prepare(OutgoingFragmentPhase.Cycle)
            return try {
                val result = fixture.transfer()
                fixture.verifyAndClose()
                result
            } finally {
                fixture.close()
            }
        }

        /**
         * Releases current invocation state if collection aborts.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Real common-service production construction; public callback controls belong to the separate connection matrix.
     */
    @State(Scope.Thread)
    public open class Server {
        /**
         * Number of independently confined actual services/players.
         */
        @JvmField
        @Param("1", "8")
        public var owners: Int = 1

        /**
         * The same logical/control corpus, including bootstrap, negotiated limits and queue rejection.
         */
        @JvmField
        @Param("Hello", "Control", "SmallAction", "OneFragment", "MultiFragment", "OneMiB", "NearLimit", "NegotiatedSmall", "ImmediateMany", "DelayedMany", "UnsentCancellation", "PartialCancellation", "EntryCapacity", "ByteCapacity")
        public var workload: String = "Hello"
        private lateinit var fixture: OutgoingFragmentFixture

        /**
         * Freezes the same immutable sources before timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = OutgoingFragmentFixture(owners, OutgoingFragmentWorkload.valueOf(workload), OutgoingFragmentRoute.Production)
        }

        /**
         * Runs the actual service setup, production transfer, byte parity and terminal release as one cycle.
         */
        public fun cycle(): Int {
            fixture.prepare(OutgoingFragmentPhase.ServerCycle)
            return try {
                val result = fixture.transfer()
                fixture.verifyAndClose()
                result
            } finally {
                fixture.close()
            }
        }

        /**
         * Releases a failed or interrupted invocation.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Shared collector deterministic preflight for every actual byte/ownership/control row.
     */
    public companion object {
        /**
         * Checks generated JMH matrix and all196 isolated/full CPU cases before any timing fork.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(OutgoingFragmentBenchmark::class.java), setOf("avgt")).size == 84)
            listOf(1, 8).forEach { owners ->
                OutgoingFragmentWorkload.entries.forEach { workload ->
                    OutgoingFragmentPhase.entries.forEach { phase ->
                        val routes = if (phase == OutgoingFragmentPhase.ServerCycle) listOf(OutgoingFragmentRoute.Production) else OutgoingFragmentRoute.entries
                        routes.forEach { route ->
                            OutgoingFragmentFixture(owners, workload, route).use { fixture ->
                                val counts = fixture.workCounts(phase)
                                repeat(2) {
                                    fixture.prepare(phase)
                                    fixture.transfer()
                                    fixture.verifyAndClose()
                                }
                                println("Outgoing fragments $owners/$workload/$phase/$route: $counts")
                            }
                        }
                    }
                }
            }
        }
    }
}
