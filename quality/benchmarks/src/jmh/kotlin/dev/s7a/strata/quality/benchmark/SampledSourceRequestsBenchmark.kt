package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Separates actual Fabric frame preparation from recurring device borrowing with native-free lifetime resources.
 * Both revisions consume the same original immutable commands; reflection adds the same call boundary to each measurement.
 * Native rendering, pixels, uploads and GPU completion require the independent loaded-client acceptance.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class SampledSourceRequestsBenchmark {
    /**
     * Reconstructs one complete prepared frame, including partitioning, ordered metadata and source-request admission.
     */
    @Benchmark
    public fun prepare(state: SourceState): Any = state.prepare()

    /**
     * Borrows the primed unchanged frame's requests, including live accounting, capacity protection and balanced pins.
     */
    @Benchmark
    public fun borrow(state: SourceState): Any = state.borrow()

    /**
     * Fixed original placement and identity counts; the composed cases additionally share each source across six tiles.
     * Unique-source controls intentionally retain the device's ordinary bounded capacity fallback.
     */
    public enum class Case(
        public val occurrences: Int,
        public val identities: Int,
        public val composed: Boolean = false,
    ) {
        DirectOne(1, 1),
        Direct64Shared(64, 1),
        Direct64Sixteen(64, 16),
        Direct64Unique(64, 64),
        Direct4096Shared(4096, 1),
        Direct4096Sixteen(4096, 16),
        Direct4096Unique(4096, 4096),
        ComposedTilesShared(64, 1, true),
        ComposedTilesSixteen(64, 16, true),
        ;

        /**
         * Original logical viewport, with one physical pixel per direct placement and six composed 256-pixel tiles.
         */
        public val viewport: IntSize
            get() = if (composed) IntSize(768, 512) else IntSize(64, 64)

        /**
         * Constructs immutable equal-pixel but referentially distinct sources outside recurring measurement.
         */
        public fun commands(): List<DrawCommand> {
            val extent = if (composed) 128 else 1
            val images = List(identities) { createDrawImage(IntSize(extent, extent)) { _, _ -> 0x804466AA.toInt() } }
            return List(occurrences) { index ->
                val x = (index % 64).toFloat()
                val y = (index / 64).toFloat()
                val destination = if (composed) FloatRect(0f, 0f, 768f, 512f) else FloatRect(x, y, x + 1f, y + 1f)
                DrawCommand.SampledImage(images[index % identities], FloatRect(0f, 0f, extent.toFloat(), extent.toFloat()), destination, if (composed) ArgbColor(0xC0BFD7EF.toInt()) else ArgbColor(-1), alphaCutoff = 0f)
            }
        }
    }

    /**
     * Resolves a supplied actual Fabric archive without loading Minecraft or allocating game resources.
     * Only the current inputs and the bounded real device cache survive between operations.
     */
    @State(Scope.Thread)
    public open class SourceState {
        /**
         * Frozen placement/identity matrix, selected through the shared generated-fixture mechanism.
         */
        @JvmField
        @Param("DirectOne", "Direct64Shared", "Direct64Sixteen", "Direct64Unique", "Direct4096Shared", "Direct4096Sixteen", "Direct4096Unique", "ComposedTilesShared", "ComposedTilesSixteen")
        public var case: Case = Case.DirectOne

        private lateinit var commands: List<DrawCommand>
        private lateinit var partition: Method
        private lateinit var inputsConstructor: Constructor<*>
        private lateinit var device: Any
        private lateinit var owner: Any
        private lateinit var requests: Any
        private lateinit var borrowMethod: Method
        private val hit: () -> Unit = {}
        private val miss: () -> Unit = {}
        private val uploaded: (DrawImage) -> Unit = {}
        private val evicted: () -> Unit = {}

        /**
         * Creates deterministic signalled fences and synchronously destroyed resources, then primes the actual device.
         * Source uploads are ownership transfers only in this JVM fixture and are never reported as native work.
         */
        @Setup(Level.Trial)
        @Suppress("LongMethod", "StringLiteralComparison") // One untimed boundary resolves external JVM member names and independently owned fixture resources, without domain-state discrimination.
        public fun setup() {
            commands = case.commands()
            val layersType = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayerKt")
            partition = layersType.declaredMethods.single { it.name == "partitionFabricMinecraftFrame" }
            inputsConstructor = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameInputs").declaredConstructors.single { it.parameterCount == 6 }
            check(inputsConstructor.trySetAccessible())
            val deviceType = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSampledImageDevice")
            val deviceConstructor = deviceType.declaredConstructors.single { it.parameterCount == 3 }
            check(deviceConstructor.trySetAccessible())
            val driverType = deviceConstructor.parameterTypes[0]
            val fenceType = driverType.getMethod("fence").returnType
            val driver =
                Proxy.newProxyInstance(driverType.classLoader, arrayOf(driverType)) { _, method, _ ->
                    when (method.name) {
                        "fence" -> Proxy.newProxyInstance(fenceType.classLoader, arrayOf(fenceType)) { _, operation, _ -> if (operation.name == "isSignalled") true else null }
                        "finish", "drainRetirements" -> null
                        else -> error("Source borrowing must not allocate native targets: ${method.name}")
                    }
                }
            val resourceType = Class.forName("dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource")
            val supports: (DrawImage) -> Boolean = { true }
            val create: (DrawImage, (Any) -> Unit) -> Any? = { _, retain ->
                var closed = false
                retain(
                    Proxy.newProxyInstance(resourceType.classLoader, arrayOf(resourceType)) { _, method, _ ->
                        when (method.name) {
                            "isDestroyed" -> closed
                            "close" -> {
                                check(closed.not())
                                closed = true
                                null
                            }

                            else -> error("Unknown fixture resource operation: ${method.name}")
                        }
                    },
                )
                null
            }
            device = deviceConstructor.newInstance(driver, supports, create)
            owner = invoke("openOwner")
            val inputs = prepare()
            requests =
                inputs.javaClass.declaredFields.firstOrNull { it.name == "sampledRequests" }?.let { field ->
                    check(field.trySetAccessible())
                    checkNotNull(field.get(inputs))
                } ?: member(inputs, "sampled")
            borrowMethod = deviceType.declaredMethods.single { it.name.substringBefore('$') == "borrow" && it.parameterCount == 6 && it.parameterTypes[1].isInstance(requests) }
            check(borrowMethod.trySetAccessible())
            val requestedImages = (requests as? List<*>) ?: (member(requests, "images") as List<*>)
            val expectedRequests = if (requests is List<*>) case.occurrences * (if (case.composed) 6 else 1) else case.identities
            check(requestedImages.size == expectedRequests)
            check((member(borrow(), "images") as Map<*, *>).size == minOf(256, case.identities))
            check(invoke("retainedResourceCount") == minOf(256, case.identities))
        }

        /**
         * Returns a fresh frame without retaining its partition or metadata in benchmark state.
         */
        internal fun prepare(): Any = inputsConstructor.newInstance(partition.invoke(null, commands, case.viewport, 1, false, false), 1, 0L, 0L, case.composed, null)

        /**
         * Completes one real device borrow and returns the closed detached lookup to the JMH blackhole.
         */
        internal fun borrow(): Any = checkNotNull(borrowMethod.invoke(device, owner, requests, hit, miss, uploaded, evicted)).also { (it as AutoCloseable).close() }

        /**
         * Runs the device's ordinary terminal path and rejects any retained resources after acknowledgement.
         */
        @TearDown(Level.Trial)
        public fun close() {
            invoke("beginShutdown")
            invoke("closeAfterFinish")
            invoke("acknowledgeAfterDrain")
            check(invoke("retainedResourceCount") == 0)
        }

        private fun invoke(name: String): Any {
            val method = device.javaClass.declaredMethods.single { it.name.substringBefore('$') == name && it.parameterCount == 0 }
            check(method.trySetAccessible())
            return method.invoke(device) ?: Unit
        }

        private fun member(
            value: Any,
            name: String,
        ): Any {
            val field = value.javaClass.getDeclaredField(name)
            check(field.trySetAccessible())
            return checkNotNull(field.get(value))
        }
    }

    /**
     * Verifies the immutable fixture matrix and complete direct pixels outside timing, without a Fabric classpath.
     * Runtime-specific request, capacity and terminal checks additionally run in every trial's setup and teardown.
     */
    public companion object {
        /**
         * Checks each original identity stream and one independent literal-pixel oracle before collection.
         */
        @JvmStatic
        public fun verifyWork() {
            Case.entries.forEach { case ->
                val commands = case.commands().map { it as DrawCommand.SampledImage }
                check(commands.size == case.occurrences)
                val identities = Collections.newSetFromMap(IdentityHashMap<DrawImage, Boolean>())
                commands.forEach { identities.add(it.image) }
                check(identities.size == case.identities)
                if (case.composed.not()) {
                    val actual = rasterizeHeadless(commands, case.viewport).copyArgb()
                    check(actual.indices.all { actual[it] == if (it < case.occurrences) 0x804466AA.toInt() else 0 })
                }
            }
        }
    }
}
