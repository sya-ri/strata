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
 * Frozen actual decoder, queue, logical assembly and common-service ingress intervals.
 * Literal sources are prepared before timing; each JMH cycle includes fresh owner/queue preparation, byte checks and release.
 * The supplemental shared CPU meter separately isolates the selected core operation without invocation-level JMH timestamps.
 */
public open class IncomingFragmentBenchmark {
    /**
     * Measures a complete lifecycle around native decode/admission; downstream byte parity and cleanup are included.
     */
    @Benchmark
    public fun admissionCycle(state: Transfer): Int = state.cycle(IncomingFragmentPhase.Admission)

    /**
     * Measures a complete lifecycle around inbox snapshots, decode, reorder and logical assembly.
     */
    @Benchmark
    public fun assemblyCycle(state: Transfer): Int = state.cycle(IncomingFragmentPhase.Assembly)

    /**
     * Measures a complete lifecycle around actual common-service ingress, including discovery, downstream parity and release.
     */
    @Benchmark
    public fun serverIngressCycle(state: Server): Int = state.cycle()

    /**
     * Shared literal source corpus and public defensive-copy control, with invocation lifecycle inside JMH timing.
     */
    @State(Scope.Thread)
    public open class Transfer {
        /**
         * Number of independently confined transport owners.
         */
        @JvmField
        @Param("1", "8")
        public var owners: Int = 1

        /**
         * Typed corpus name decoded once from the JMH boundary.
         */
        @JvmField
        @Param("Minimum", "Small", "Maximum", "OneMiB", "NearLimit", "Reversed", "Gap", "Duplicate", "Stale", "OtherIncarnation", "Discovery", "NegotiatedSmall")
        public var workload: String = "Minimum"

        /**
         * Production ownership handoff or unchanged public defensive-copy control.
         */
        @JvmField
        @Param("Production", "Public")
        public var route: String = "Production"
        private lateinit var fixture: IncomingFragmentFixture

        /**
         * Freezes literal source envelopes before any operation interval.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = IncomingFragmentFixture(owners, IncomingFragmentWorkload.valueOf(workload), IncomingFragmentRoute.valueOf(route))
        }

        /**
         * Runs preparation, the selected core operation, complete parity and release as one JMH cycle.
         */
        public fun cycle(phase: IncomingFragmentPhase): Int {
            fixture.prepare(phase)
            return try {
                val result = fixture.receive()
                fixture.verifyAndClose()
                result
            } finally {
                fixture.close()
            }
        }

        /**
         * Releases an incomplete invocation if a collector aborts.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * Actual common-service production entry point; no synthetic public-route substitution for the server control.
     */
    @State(Scope.Thread)
    public open class Server {
        /**
         * Number of actual independent services/players in the invocation.
         */
        @JvmField
        @Param("1", "8")
        public var owners: Int = 1

        /**
         * The same bounded byte/order corpus used by the isolated and assembly intervals.
         */
        @JvmField
        @Param("Minimum", "Small", "Maximum", "OneMiB", "NearLimit", "Reversed", "Gap", "Duplicate", "Stale", "OtherIncarnation", "Discovery", "NegotiatedSmall")
        public var workload: String = "Minimum"
        private lateinit var fixture: IncomingFragmentFixture

        /**
         * Freezes independent literal sources before timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = IncomingFragmentFixture(owners, IncomingFragmentWorkload.valueOf(workload), IncomingFragmentRoute.Production)
        }

        /**
         * Runs actual service preparation, core ingress, complete parity and release inside one JMH cycle.
         */
        public fun cycle(): Int {
            fixture.prepare(IncomingFragmentPhase.ServerIngress)
            return try {
                val result = fixture.receive()
                fixture.verifyAndClose()
                result
            } finally {
                fixture.close()
            }
        }

        /**
         * Releases any incomplete operation on collector failure.
         */
        @TearDown(Level.Trial)
        public fun close() {
            fixture.close()
        }
    }

    /**
     * The shared collector invokes complete deterministic work checks before any timing fork.
     */
    public companion object {
        /**
         * Preserves every matrix row and checks actual byte/copy work alongside ordered output and terminal release.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(IncomingFragmentBenchmark::class.java), setOf("avgt")).size == 120)
            listOf(1, 8).forEach { owners ->
                IncomingFragmentWorkload.entries.forEach { workload ->
                    IncomingFragmentPhase.entries.forEach { phase ->
                        val routes = if (phase == IncomingFragmentPhase.ServerIngress) listOf(IncomingFragmentRoute.Production) else IncomingFragmentRoute.entries
                        routes.forEach { route ->
                            verifyCombination(owners, workload, phase, route)
                        }
                    }
                }
            }
        }

        private fun verifyCombination(
            owners: Int,
            workload: IncomingFragmentWorkload,
            phase: IncomingFragmentPhase,
            route: IncomingFragmentRoute,
        ) {
            IncomingFragmentFixture(owners, workload, route).use { fixture ->
                val counts = fixture.workCounts()
                repeat(2) {
                    fixture.prepare(phase)
                    check(fixture.receive() == owners * counts.getValue("native_packets").toInt())
                    fixture.verifyAndClose()
                }
                println("Incoming fragments $owners/$workload/$phase/$route: $counts")
            }
        }
    }
}
