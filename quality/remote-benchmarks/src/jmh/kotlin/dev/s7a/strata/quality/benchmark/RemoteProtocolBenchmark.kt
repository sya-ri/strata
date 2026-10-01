package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteDeclaration
import dev.s7a.strata.runtime.remote.RemoteFraming
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteNode
import dev.s7a.strata.runtime.remote.RemotePatch
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Native-free actual remote protocol work; these JVM operations do not claim socket or Paper/Velocity timing.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class RemoteProtocolBenchmark {
    /**
     * Builds the actual sparse declaration patch between immutable prepared trees.
     */
    @Benchmark
    public fun diff(state: Protocol): RemotePatch = RemotePatch.between(state.before, state.after)

    /**
     * Validates and applies one real patch without changing the previous immutable tree.
     */
    @Benchmark
    public fun apply(state: Protocol): RemoteTree = state.patch.apply(state.before)

    /**
     * Encodes and decodes the real update message schema, including all changed declarations.
     */
    @Benchmark
    public fun updateCodec(state: Protocol): RemoteMessage = state.codec.decode(state.codec.encode(state.update))

    /**
     * Encodes and decodes the real full snapshot, including bounded topology validation.
     */
    @Benchmark
    public fun snapshotCodec(state: Protocol): RemoteMessage = state.codec.decode(state.codec.encode(state.snapshot))

    /**
     * Fragments and reassembles prepared update bytes using real connection-owned transport lifetimes.
     */
    @Benchmark
    public fun fragments(state: Protocol): ByteArray = state.fragmentRoundTrip()

    /**
     * Immutable prepared protocol inputs on one JMH worker.
     */
    @State(Scope.Thread)
    public open class Protocol {
        /**
         * Normal count and the actual default negotiated tree-node limit.
         */
        @JvmField
        @Param("100", "8192")
        public var nodes: Int = 100

        /**
         * Stable, single-record and complete record changes share the same retained node identities.
         */
        @JvmField
        @Param
        public var change: RemoteChange = RemoteChange.Stable

        /**
         * Actual typed schema codec shared by both measured directions.
         */
        public val codec: RemoteMessageCodec = RemoteMessageCodec()
        private val type = ProjectionType(ResourceId("strata_benchmark", "node"))

        /**
         * Original immutable topology prepared outside measured work.
         */
        public lateinit var before: RemoteTree

        /**
         * Candidate immutable topology with exactly the configured record changes.
         */
        public lateinit var after: RemoteTree

        /**
         * Precomputed patch for isolating application and codec work from diff construction.
         */
        public lateinit var patch: RemotePatch

        /**
         * Actual encoded update message's immutable logical input.
         */
        public lateinit var update: RemoteMessage.Update

        /**
         * Actual complete snapshot message, using the same tree and capability declarations.
         */
        public lateinit var snapshot: RemoteMessage.Snapshot
        private lateinit var encoded: ByteArray

        /**
         * Builds real bounded trees and codec inputs before JMH sampling begins.
         */
        @Setup(Level.Trial)
        public fun setup() {
            require(nodes in setOf(100, RemoteLimits().treeNodes))
            before = tree(candidate = false)
            after = tree(candidate = true)
            patch = RemotePatch.between(before, after)
            update = RemoteMessage.Update(1, 1, 2, patch)
            snapshot = RemoteMessage.Snapshot(1, 1, ProjectionValue.Text("Protocol stress"), after)
            encoded = codec.encode(update)
        }

        /**
         * Uses each framing owner exactly once and closes both on every return or failure.
         * The logical timestamp is a fixed transport test input; it is not a performance clock.
         */
        public fun fragmentRoundTrip(): ByteArray {
            var complete: ByteArray? = null
            RemoteFraming(RemoteLimits()).use { sender ->
                RemoteFraming(RemoteLimits()).use { receiver ->
                    sender.send(encoded) { fragment ->
                        val result = receiver.receive(fragment, 0)
                        if (result != null) complete = result
                    }
                }
            }
            return checkNotNull(complete)
        }

        private fun tree(candidate: Boolean): RemoteTree =
            RemoteTree(
                1,
                (1..nodes).map { index ->
                    val changed =
                        candidate &&
                            when (change) {
                                RemoteChange.Stable -> false
                                RemoteChange.Single -> index == nodes
                                RemoteChange.All -> true
                            }
                    RemoteNode(RemoteDeclaration(index.toLong(), type, ProjectionValue.Integer(if (changed) 1 else 0)), children = if (index == 1) (2..nodes).map(Int::toLong) else emptyList())
                },
            )
    }
}
