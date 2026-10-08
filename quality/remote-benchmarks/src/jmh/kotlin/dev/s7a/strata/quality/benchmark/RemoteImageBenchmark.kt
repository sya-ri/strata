@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.Node
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.projection.DeclarationProjection
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteImageCodec
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Public codec and real server-session image projection, with only APIs present on both target archives.
 * Prepared immutable images and independent pixel/wire oracles stay outside measured operations.
 * This fixture measures logical declarations, not native rendering, network throughput or GUI frames.
 */
public open class RemoteImageBenchmark {
    /**
     * Executes the selected actual codec or full server projection; each result is consumed without a history.
     */
    @Benchmark
    public fun process(scene: Scene): Any = scene.perform()

    /**
     * Repeated square inputs and a rectangular image close to the standalone negotiated message byte bound.
     */
    public enum class Extent(public val size: IntSize) {
        Square256(IntSize(256, 256)),
        Square512(IntSize(512, 512)),
        Square1024(IntSize(1024, 1024)),
        NearStandaloneLimit(IntSize(4096, 511)),
    }

    /**
     * Cold, reused, replacement, sharing, lifecycle-retirement and structural-resource controls.
     */
    public enum class Workload {
        ColdEncode,
        IdleProjection,
        SharedNodes,
        IndependentSessions,
        Replacements,
        EqualIdentityChurn,
        RemovalReadmission,
        ResourceReference,
        EmptyProjection,
    }

    /**
     * Two fixed immutable variants, current owners and cumulative message counters on one JMH worker.
     * Shared large-image trees declare enough wire budget for every repeated payload; no wire deduplication is assumed.
     */
    @State(Scope.Thread)
    public open class Scene(private val reconstructionMillis: Long = 1000) : AutoCloseable {
        /**
         * Prepared size and independent raw-byte corpus.
         */
        @JvmField
        @Param
        public var extent: Extent = Extent.Square256

        /**
         * Complete operation boundary selected by the generated inventory.
         */
        @JvmField
        @Param
        public var workload: Workload = Workload.ColdEncode

        private lateinit var first: DrawImage
        private lateinit var second: DrawImage
        private lateinit var codec: RemoteImageCodec
        private lateinit var limits: RemoteLimits
        private var owners = emptyList<RemoteServerSession>()
        private var selected = false
        private var visible = true
        private var messages = 0L
        private var updates = 0L
        private var closes = 0
        private var last: RemoteMessage? = null
        private val resource = ImageSource.Resource(ResourceId("benchmark", "textures/image.png"))

        /**
         * Creates both immutable inputs and admitted steady session owners outside timed intervals.
         */
        @Setup(Level.Trial)
        public fun setup() {
            visible = workload != Workload.EmptyProjection
            first = createDrawImage(extent.size) { x, y -> x * 8191 + y * 65537 }
            second = if (workload == Workload.EqualIdentityChurn) createDrawImage(extent.size, first.copyArgb()) else createDrawImage(extent.size) { x, y -> x * 8191 + y * 65537 + 1 }
            val rawBytes = extent.size.width * extent.size.height * Int.SIZE_BYTES
            val messageBytes = if (workload == Workload.SharedNodes) rawBytes * 2 + 4096 else RemoteLimits().messageBytes
            limits = RemoteLimits(messageBytes = messageBytes, pendingBytes = maxOf(RemoteLimits().pendingBytes, messageBytes * 2), reconstructionMillis = reconstructionMillis)
            codec = RemoteImageCodec(limits.messageBytes)
            if (workload != Workload.ColdEncode && workload != Workload.ResourceReference) {
                val count = if (workload == Workload.IndependentSessions) 2 else 1
                owners = (1..count).map { identity ->
                    val leaves = if (workload == Workload.SharedNodes) 2 else 1
                    val root = ImageElement({ if (visible) currentImage() else null }, List(leaves - 1) { ImageElement({ currentImage() }) })
                    RemoteServerSession(identity.toLong(), ProjectionValue.Absent, setOf(ImageElement.PROJECTION), limits, send = ::receive) { root }.also { it.tick() }
                }
                verifyWire(checkNotNull(last))
            }
        }

        /**
         * Runs exactly one declared operation; replacements/readmission alternate fixed inputs without creating images.
         */
        public fun perform(): Any =
            when (workload) {
                Workload.ColdEncode -> codec.encode(ImageSource.Pixels(first))
                Workload.ResourceReference -> codec.encode(resource)
                else -> {
                    if (workload == Workload.Replacements || workload == Workload.EqualIdentityChurn) selected = selected.not()
                    if (workload == Workload.RemovalReadmission) visible = visible.not()
                    owners.forEach(RemoteServerSession::tick)
                    messages
                }
            }

        /**
         * Complete per-case admission and denominator controls for shared CPU evidence.
         */
        public fun controls(): Map<String, Long> =
            mapOf(
                "source_pixel_bytes" to extent.size.width.toLong() * extent.size.height * Int.SIZE_BYTES,
                "message_bytes" to limits.messageBytes.toLong(),
                "pending_bytes" to limits.pendingBytes.toLong(),
                "sessions" to owners.size.toLong(),
                "nodes" to (if (workload == Workload.SharedNodes) 2L else if (owners.isEmpty()) 0L else owners.size.toLong()),
            )

        /**
         * Checks actual repeated work, independent endian bytes, wire admission, replacement and owner cleanup.
         * Small/control cases are retained even if their measured CPU or allocation does not improve.
         */
        public fun verifyWork() {
            val original = first.copyArgb()
            val expected = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output -> original.forEach(output::writeInt) } }.toByteArray()
            val encoded = codec.encode(ImageSource.Pixels(first)) as ProjectionValue.Sequence
            check((encoded.values[3] as ProjectionValue.Bytes).toByteArray().contentEquals(expected))
            check(codec.decode(encoded) == ImageSource.Pixels(first))
            val initialMessages = messages
            val initialUpdates = updates
            perform()
            perform()
            when (workload) {
                Workload.IdleProjection, Workload.SharedNodes, Workload.IndependentSessions, Workload.EqualIdentityChurn, Workload.EmptyProjection -> check(messages == initialMessages && updates == initialUpdates)
                Workload.Replacements, Workload.RemovalReadmission -> check(updates == initialUpdates + 2)
                Workload.ColdEncode -> check(perform() == encoded)
                Workload.ResourceReference -> check(codec.decode(perform() as ProjectionValue) == resource)
            }
            check(first.copyArgb().contentEquals(original))
            if (owners.isNotEmpty()) {
                owners.forEach { check(it.status == RemoteSessionStatus.Open) }
                verifyWire(checkNotNull(last))
            }
            val count = owners.size
            close()
            check(closes == count)
        }

        /**
         * Releases every current server, clearing the fixture's last-message reference before returning.
         */
        @TearDown(Level.Trial)
        override fun close() {
            val current = owners
            owners = emptyList()
            current.forEach(RemoteServerSession::close)
            last = null
        }

        private fun currentImage(): DrawImage = if (selected) second else first

        private fun receive(message: RemoteMessage) {
            messages++
            when (message) {
                is RemoteMessage.Update -> updates++
                is RemoteMessage.Close -> closes++
                else -> Unit
            }
            last = message
        }

        private fun verifyWire(message: RemoteMessage) {
            val wire = RemoteMessageCodec(limits)
            val bytes = wire.encode(message)
            check(bytes.size <= limits.messageBytes)
            check(wire.decode(bytes) == message)
            val tree = when (message) {
                is RemoteMessage.Snapshot -> message.tree
                is RemoteMessage.Update -> {
                    message.patch.changed.forEach { check(codec.decode(it.declaration.value) == ImageSource.Pixels(first)) }
                    null
                }
                else -> error("Expected a declaration message")
            }
            if (tree != null) verifyTree(tree)
        }

        private fun verifyTree(tree: RemoteTree) {
            tree.nodes.values.forEach { node ->
                when (val value = node.declaration.value) {
                    is ProjectionValue.Sequence -> check(codec.decode(value) == ImageSource.Pixels(first))
                    ProjectionValue.Absent -> check(visible.not())
                    else -> error("Expected an optional image declaration")
                }
            }
        }
    }

    /**
     * Consumer-owned optional-image primitive through the unchanged public Element/Node projection SPI.
     * No new standard built-in or registration is introduced; clients are outside this native-free boundary.
     */
    private class ImageElement(
        provider: () -> DrawImage?,
        children: List<Element> = emptyList(),
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        override val projection = DeclarationProjection(PROJECTION, provider) { read, scope -> read()?.let(scope::image) ?: ProjectionValue.Absent }

        private class ImageNode : Node()

        companion object {
            val PROJECTION = ProjectionType(ResourceId("benchmark", "optional_image"))
            private val TYPE = ElementType(ImageElement::class, ImageNode::class, { _ -> }, { _ -> ImageNode() }, { _, _, _ -> DirtyMask.None })
        }
    }

    /**
     * Generated discovery and independent verification share the existing generic remote workload launcher.
     */
    public companion object {
        /**
         * Requires every compiled extent/workload combination and actual work/owner release.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(RemoteImageBenchmark::class.java), setOf("avgt")).size == 36)
            for (extent in Extent.entries) {
                for (workload in Workload.entries) {
                    Scene().use { scene ->
                        scene.extent = extent
                        scene.workload = workload
                        scene.setup()
                        scene.verifyWork()
                    }
                }
            }
        }
    }
}
