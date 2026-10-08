package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.BenchmarkParams

/**
 * Frozen actual decoder, queue, logical assembly and common-service ingress intervals.
 * Sources and per-invocation owners are prepared before timing; full byte and terminal checks follow each invocation.
 */
public open class IncomingFragmentBenchmark {
    /**
     * Measures native decoding and real bounded reorder admission without inbox or assembly copies.
     */
    @Benchmark
    public fun admission(state: Transfer): Int = state.receive()

    /**
     * Measures complete native-envelope-to-logical-message work, retaining the native snapshot and assembly copy.
     */
    @Benchmark
    public fun assembly(state: Transfer): Int = state.receive()

    /**
     * Measures native inbox submission and actual common-service bounded ingress, with downstream assembly untimed.
     */
    @Benchmark
    public fun serverIngress(state: Server): Int = state.receive()

    /**
     * Shared source corpus and public defensive-copy control, with invocation lifecycle outside timing.
     */
    @State(Scope.Thread)
    public open class Transfer {
        /** Number of independently confined transport owners. */
        @JvmField
        @Param("1", "8")
        public var owners: Int = 1

        /** Typed corpus name decoded once from the JMH boundary. */
        @JvmField
        @Param("Minimum", "Small", "Maximum", "OneMiB", "NearLimit", "Reversed", "Gap", "Duplicate", "Stale", "OtherIncarnation", "Discovery", "NegotiatedSmall")
        public var workload: String = "Minimum"

        /** Production ownership handoff or unchanged public defensive-copy control. */
        @JvmField
        @Param("Production", "Public")
        public var route: String = "Production"
        private lateinit var fixture: IncomingFragmentFixture

        /** Freezes literal source envelopes before any operation interval. */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = IncomingFragmentFixture(owners, IncomingFragmentWorkload.valueOf(workload), IncomingFragmentRoute.valueOf(route))
        }

        /** Creates fresh queues according to the selected compiled method. */
        @Setup(Level.Invocation)
        public fun prepare(parameters: BenchmarkParams) {
            val phase = IncomingFragmentPhase.valueOf(parameters.benchmark.substringAfterLast('.').replaceFirstChar(Char::uppercaseChar))
            fixture.prepare(phase)
        }

        /** Performs one current invocation through the actual runtime archive. */
        public fun receive(): Int = fixture.receive()

        /** Checks exact output and releases every current-owner resource after timing. */
        @TearDown(Level.Invocation)
        public fun verify() {
            fixture.verifyAndClose()
        }

        /** Releases an incomplete invocation if a collector aborts. */
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
        /** Number of actual independent services/players in the invocation. */
        @JvmField
        @Param("1", "8")
        public var owners: Int = 1

        /** The same bounded byte/order corpus used by the isolated and assembly intervals. */
        @JvmField
        @Param("Minimum", "Small", "Maximum", "OneMiB", "NearLimit", "Reversed", "Gap", "Duplicate", "Stale", "OtherIncarnation", "Discovery", "NegotiatedSmall")
        public var workload: String = "Minimum"
        private lateinit var fixture: IncomingFragmentFixture

        /** Freezes independent literal sources before timing. */
        @Setup(Level.Trial)
        public fun setup() {
            fixture = IncomingFragmentFixture(owners, IncomingFragmentWorkload.valueOf(workload), IncomingFragmentRoute.Production)
        }

        /** Creates actual service peers and admits discovery before timing. */
        @Setup(Level.Invocation)
        public fun prepare() {
            fixture.prepare(IncomingFragmentPhase.ServerIngress)
        }

        /** Calls actual native inbox admission and common server receive phases. */
        public fun receive(): Int = fixture.receive()

        /** Verifies every assembled byte and terminal queue/writer release after timing. */
        @TearDown(Level.Invocation)
        public fun verify() {
            fixture.verifyAndClose()
        }

        /** Releases any incomplete operation on collector failure. */
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
            }
        }
    }
}
