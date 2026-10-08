@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteClientSession
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Actual public TiledImage projection consumers, separate from isolated layout and portable dirty frames.
 * Includes real schema encoders and diffing; the complete path also codes, applies, reconstructs and frames a client.
 */
public open class TiledImageRemoteBenchmark {
    /**
     * Publishes one public transform and ticks the actual server declaration owner.
     */
    @Benchmark
    public fun serverProjection(state: Peer): Int = state.serverProjection()

    /**
     * Runs the complete unchanged server projection and normal diff suppression as a clean control.
     */
    @Benchmark
    public fun idleProjection(state: Peer): Int = state.idleProjection()

    /**
     * Includes projection, current message encoding/decoding, validated client application and a real client frame.
     */
    @Benchmark
    public fun projectedConsumption(state: Peer): RuntimeUiFrame = state.projectedConsumption()

    /**
     * Current server/client owners and one latest outbound message, with no packet or declaration history.
     */
    @State(Scope.Thread)
    public open class Peer {
        /**
         * Shared input geometry and unchanged image/entry admission policy.
         */
        @JvmField
        @Param
        public var case: TiledImageBenchmarkInput.Case = TiledImageBenchmarkInput.Case.Sparse

        /**
         * Real public transform operation, identical to portable fixture rows.
         */
        @JvmField
        @Param
        public var change: TiledImageBenchmarkInput.Change = TiledImageBenchmarkInput.Change.SameRangePan

        /**
         * Current source observations for optional untimed checks.
         */
        public lateinit var input: TiledImageBenchmarkInput
            private set

        private lateinit var server: RemoteServerSession
        private lateinit var client: RemoteClientSession
        private lateinit var host: RuntimeUiSession
        private lateinit var constraints: Constraints
        private val codec = RemoteMessageCodec()
        private var latest: RemoteMessage? = null

        /**
         * Primes actual server projection and actual client rendering outside measured operations.
         */
        @Setup(Level.Trial)
        public fun setup() {
            input = TiledImageBenchmarkInput(case)
            val registry = RemoteRegistry().also(RemoteBuiltins::register)
            server = RemoteServerSession(1, ProjectionValue.Text("Tiled image topology"), registry.types, send = { latest = it }, content = input::element)
            server.tick()
            val snapshot = codec.decode(codec.encode(checkNotNull(latest))) as RemoteMessage.Snapshot
            client = RemoteClientSession(snapshot, registry, send = {})
            val content = client.definition(UiText.Literal("Tiled image topology")).transfer().content
            host = createRuntimeUiSession { evaluateComponentTree(content) }
            constraints = Constraints.fixed(input.size.width, input.size.height)
            host.attach()
            host.frame(constraints)
            latest = null
        }

        /**
         * Performs real declaration preparation, property encoding, owner diffing and bounded emission.
         */
        public fun serverProjection(): Int {
            input.advance(change)
            latest = null
            server.tick()
            return server.nodeCount
        }

        /**
         * Keeps normal property encoding and diffing on unchanged inputs; does not implement idle projection reuse.
         */
        public fun idleProjection(): Int {
            latest = null
            server.tick()
            check(latest == null)
            return server.nodeCount
        }

        /**
         * Consumes the actual emitted patch; unchanged controls retain normal server and client work.
         */
        public fun projectedConsumption(): RuntimeUiFrame {
            serverProjection()
            val outgoing = latest
            if (outgoing != null) {
                when (val decoded = codec.decode(codec.encode(outgoing))) {
                    is RemoteMessage.Snapshot -> client.receive(decoded)
                    is RemoteMessage.Update -> client.receive(decoded)
                    else -> error("Unexpected TiledImage projection message.")
                }
            }
            return host.frame(constraints)
        }

        /**
         * Returns an independently constructed local public frame for optional pixel/command admission only.
         */
        public fun localFrame(): RuntimeUiFrame =
            createRuntimeUiSession(content = input::element).use { local ->
                local.attach()
                local.frame(constraints)
            }

        /**
         * Reads actual authoritative current-grid metadata only during optional untimed admission.
         */
        internal fun topology(): TiledImageTopologyObservation.Snapshot {
            val reflected = server.javaClass.getDeclaredField("session")
            check(reflected.trySetAccessible())
            val owner = reflected.get(server) as RuntimeUiSession
            return TiledImageTopologyObservation.capture(owner, input)
        }

        /**
         * Releases client rendering, prepared state and authoritative subscriptions on their owner.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                host.close()
            } finally {
                try {
                    client.close()
                } finally {
                    server.close()
                }
            }
            input.verifyReleased()
            check(client.nodeCount == 0 && server.nodeCount == 0)
            check(client.status is RemoteSessionStatus.Closed && server.status is RemoteSessionStatus.Closed)
            latest = null
        }
    }

    /**
     * Deterministic whole-consumer admission for generated rows, without clocks or predicted counters.
     */
    public companion object {
        /**
         * Checks real remote reconstruction against fresh local commands and balanced terminal owners.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(TiledImageRemoteBenchmark::class.java), setOf("avgt")).size == 132)
            for (case in TiledImageBenchmarkInput.Case.entries) {
                for (change in TiledImageBenchmarkInput.Change.entries) {
                    val projection = peer(case, change)
                    try {
                        val before = projection.topology()
                        repeat(8) { check(0 < projection.serverProjection()) }
                        val after = projection.topology()
                        TiledImageTopologyObservation.record(listOf(case.name, change.name, "serverProjection").joinToString(":"), after, projection.input, before.identity === after.identity)
                        check(0 < projection.idleProjection())
                        check(projection.input.active <= projection.input.policy.maxEntries)
                    } finally {
                        projection.close()
                    }
                    val consumer = peer(case, change)
                    try {
                        val first = consumer.projectedConsumption()
                        val old = first.drawCommands.filterIsInstance<DrawCommand.SampledImage>().map { it.image.copyArgbPixels().toList() }
                        repeat(8) {
                            val remote = consumer.projectedConsumption()
                            val local = consumer.localFrame()
                            val remoteSamples = remote.drawCommands.filterIsInstance<DrawCommand.SampledImage>()
                            val localSamples = local.drawCommands.filterIsInstance<DrawCommand.SampledImage>()
                            check(remoteSamples.size == localSamples.size)
                            remoteSamples.zip(localSamples).forEach { (actual, expected) ->
                                check(actual.destination == expected.destination && actual.source == expected.source)
                                check(actual.image.copyArgbPixels().contentEquals(expected.image.copyArgbPixels()))
                            }
                            check(remote.drawCommands.filterIsInstance<DrawCommand.FillRectangle>() == local.drawCommands.filterIsInstance<DrawCommand.FillRectangle>())
                        }
                        check(old == first.drawCommands.filterIsInstance<DrawCommand.SampledImage>().map { it.image.copyArgbPixels().toList() })
                    } finally {
                        consumer.close()
                    }
                }
            }
        }

        private fun peer(
            case: TiledImageBenchmarkInput.Case,
            change: TiledImageBenchmarkInput.Change,
        ): Peer =
            Peer().apply {
                this.case = case
                this.change = change
                setup()
            }
    }
}
