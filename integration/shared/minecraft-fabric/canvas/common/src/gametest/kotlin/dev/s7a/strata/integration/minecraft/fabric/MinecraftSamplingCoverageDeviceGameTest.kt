package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevice
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceManager
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Exercises the genuine sampled-image manager with actual native uploads and fences on the loaded render thread.
 * The gate delays reporting completion of a real native fence; it never substitutes a CPU resource or fake texture.
 * Queue markers here are controlled manager inputs, not proof of actual GUI consumption or presenter frequency.
 * The separate source/class provenance is recorded even when a development directory cannot qualify as an archive.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftSamplingCoverageDeviceGameTest {
    private val alphas = intArrayOf(0, 1, 127, 254, 255)

    /**
     * Runs bounded ownership and failure controls, then writes their partial loaded evidence without passing controls 29/30.
     * Every operation and cleanup is client-thread confined; the runner alone writes its new receipt.
     * Cleanup attempts all terminal phases and preserves the original injected or unexpected failure.
     */
    internal fun run(context: MinecraftCanvasTestContext) {
        val proof =
            context.onClient {
                val driverClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricNativeCanvasDriver")
                val driver = driverClass.getDeclaredField("INSTANCE").get(null) as NativeCanvasDriver
                val fixture = Fixture(driver)
                val result = runCatching { verify(fixture) }
                runCanvasTestCleanup(result.exceptionOrNull(), fixture::close)
                result.getOrThrow()
            }
        PerformanceJson.writeNew(context.outputDirectory.resolve("sampling-coverage-device-controls.json"), proof)
    }

    @Suppress("LongMethod") // Keep every assertion before terminal proof publication; helper calls never report a full loaded-control pass.
    private fun verify(fixture: Fixture): JsonObject {
        val first = verifyIdentityAndRetirement(fixture)
        verifyFailureCleanup(fixture)
        val mapClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSamplingMap", false, javaClass.classLoader)
        return JsonObject().apply {
            addProperty("scope", "Loaded native sampled-device ownership with controlled queue markers and gated real native fences; presenter and actual GUI-consumer assertions remain separate")
            addProperty("fullLoadedControlsPassed", false)
            addProperty("runtimeArchiveSha256", ArtifactIdentity.fullCodeSource(mapClass))
            addProperty("samplingMapClassSha256", LoadedArtifactMetadata.captureClassHashes(listOf(mapClass)).get(mapClass.name).asString)
            val targets = mapOf("sampling-map" to mapClass.name, "sampled-device" to fixture.type.name)
            add("actualLoadedRuntime", LoadedArtifactMetadata.capture(checkNotNull(mapClass.classLoader), targets, targets.keys))
            add("probeClassHashes", LoadedArtifactMetadata.captureClassHashes(listOf(MinecraftSamplingCoverageDeviceGameTest::class.java)))
            add(
                "observations",
                JsonObject().apply {
                    addProperty("sourceIdentityVsEqualPixels", true)
                    addProperty("sourceReplacement", true)
                    addProperty("ownerIsolation", true)
                    addProperty("pinnedThroughPreparation", true)
                    addProperty("controlledQueueMarkerRetains", true)
                    addProperty("gatedRealNativeFenceRetains", true)
                    addProperty("acquisitionCallbackFailure", true)
                    addProperty("failureAfterActualUpload", true)
                    addProperty("primaryFailurePreserved", true)
                    addProperty("deviceTerminalRelease", true)
                    addProperty("zeroRetainedOwners", true)
                    addProperty("zeroRetainedSources", true)
                    addProperty("nativeResourcesDestroyed", true)
                },
            )
            addProperty("actualNativeUploads", fixture.resources.size)
            addProperty("sourceWidth", first.size.width)
            addProperty("sourceHeight", first.size.height)
            val pixels = first.copyArgb()
            val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).also { it.asIntBuffer().put(pixels) }.array()
            addProperty("sourcePixelsSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            addProperty("retainedResourcesAfter", fixture.count())
            addProperty("retainedBytesAfter", fixture.bytes())
        }
    }

    /**
     * Verifies sources selected by identity remain pinned through retirement and gated native fences.
     */
    private fun verifyIdentityAndRetirement(fixture: Fixture): DrawImage {
        val first = image()
        val equalPixels = image()
        check(first !== equalPixels && first.copyArgb().contentEquals(equalPixels.copyArgb()))
        val owner = fixture.open()
        val independent = fixture.open()
        val borrowed = fixture.borrow(owner, listOf(first))
        val texture = fixture.texture(borrowed, first)
        check(texture != null && fixture.count() == 1 && fixture.pins() == 1)
        fixture.borrow(independent, listOf(first)).use { shared -> check(fixture.texture(shared, first) === texture) }
        check(fixture.count() == 1 && fixture.owners() == 2)
        fixture.borrow(owner, listOf(equalPixels)).use { replacement -> check(fixture.texture(replacement, equalPixels) !== texture) }
        check(fixture.count() == 2 && fixture.pins() == 1)
        fixture.release(owner)
        check(fixture.owners() == 1 && fixture.count() == 2 && fixture.pins() == 1)
        fixture.queued(borrowed, first)
        fixture.release(independent)
        check(fixture.owners() == 0 && fixture.count() == 2 && fixture.pins() == 1)
        borrowed.close()
        fixture.manager.consumed()
        fixture.manager.poll()
        check(fixture.count() == 2 && fixture.pins() == 0 && fixture.nativeOwnersOpen())

        return first
    }

    /**
     * Preserves acquisition and upload failures through complete terminal resource release.
     */
    private fun verifyFailureCleanup(fixture: Fixture) {
        val preparationOwner = fixture.open()
        val preparationFailure = IllegalStateException("Expected sampled acquisition callback failure")
        val before = fixture.count()
        check(runCatching { fixture.borrow(preparationOwner, listOf(image()), preparationFailure) }.exceptionOrNull() === preparationFailure)
        check(fixture.count() == before && fixture.pins() == 0)
        fixture.release(preparationOwner)

        val failureOwner = fixture.open()
        val uploadFailure = IllegalStateException("Expected failure after an actual native sampled-source upload")
        fixture.uploadFailure = uploadFailure
        check(runCatching { fixture.borrow(failureOwner, listOf(image())) }.exceptionOrNull() === uploadFailure)
        check(fixture.count() == before + 1 && fixture.pins() == 0)
        fixture.release(failureOwner)
        fixture.close()
        check(fixture.count() == 0 && fixture.bytes() == 0L && fixture.owners() == 0)
        check(fixture.resources.size == 3 && fixture.resources.all(NativeGuiResource::isDestroyed))
        check(fixture.driver.fences.isNotEmpty() && fixture.driver.fences.all { it.closed })
    }

    private fun image(): DrawImage =
        createDrawImage(IntSize(64, 48)) { x, y ->
            val alpha = alphas[(x + y * 3) % 5]
            (alpha shl 24) or (((x * 37 + y * 11) and 255) shl 16) or (((x * 13 + y * 43) and 255) shl 8) or ((x * 71 + y * 19) and 255)
        }

    /**
     * Calls genuine internal archive members; reflection is resolved outside each ownership operation.
     */
    @Suppress("TooManyFunctions") // One bounded actual-device fixture owns the resolved reflection and terminal cleanup.
    private class Fixture(
        nativeDriver: NativeCanvasDriver,
    ) : AutoCloseable {
        val driver = GatedDriver(nativeDriver)
        private val terminalDevice = NativeCanvasDevice(driver)
        val type: Class<*> = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSampledImageDevice")
        val resources = ArrayList<NativeGuiResource>()
        var uploadFailure: Throwable? = null
        private var closed = false
        private val borrows = ArrayList<AutoCloseable>()
        private val supports = supportMethod()
        private val textureClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTexture")
        private val companion = checkNotNull(textureClass.getDeclaredField("Companion").get(null))
        private val create = method(companion.javaClass, "create", DrawImage::class.java)
        private val ownerMethod = method(type, "openOwner")
        private val borrowMethod = method(type, "borrow")
        private val releaseMethod = method(type, "release")
        private val ownerClass = Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSampledImageDevice\$Borrow")
        private val textureMethod = method(ownerClass, "texture")
        private val queueMethod = method(ownerClass, "queued")
        val manager: NativeGuiResourceManager

        init {
            val supportsImage: (DrawImage) -> Boolean = { supports.invoke(null, it) as Boolean }
            val createTexture: (DrawImage, (NativeGuiResource) -> Unit) -> Any? = { image, retain ->
                val capture: (NativeGuiResource) -> Unit = { resource ->
                    resources += resource
                    retain(resource)
                }
                val result = original { create.invoke(companion, image, capture) }
                uploadFailure?.let { failure ->
                    uploadFailure = null
                    throw failure
                }
                result
            }
            manager = type.declaredConstructors.single { it.parameterCount == 3 }.newInstance(driver, supportsImage, createTexture) as NativeGuiResourceManager
            terminalDevice.registerGuiResourceManager(manager)
        }

        fun open(): Any = checkNotNull(ownerMethod.invoke(manager))

        fun borrow(
            owner: Any,
            images: List<DrawImage>,
            missFailure: Throwable? = null,
        ): AutoCloseable {
            val hit: () -> Unit = {}
            val miss: () -> Unit = { missFailure?.let { throw it } }
            val uploaded: (DrawImage) -> Unit = {}
            val evicted: () -> Unit = {}
            val result = original { borrowMethod.invoke(manager, owner, images, hit, miss, uploaded, evicted) } as AutoCloseable
            borrows += result
            return result
        }

        fun texture(
            borrow: AutoCloseable,
            image: DrawImage,
        ): Any? = textureMethod.invoke(borrow, image)

        fun queued(
            borrow: AutoCloseable,
            image: DrawImage,
        ) {
            queueMethod.invoke(borrow, image)
        }

        fun release(owner: Any) {
            original { releaseMethod.invoke(manager, owner) }
        }

        fun count(): Int = manager.retainedResourceCount()

        fun bytes(): Long = manager.retainedResourceBytes()

        fun owners(): Int = (field(manager, "owners") as Set<*>).size

        fun pins(): Int = (field(manager, "entries") as Set<*>).sumOf { field(checkNotNull(it), "pins") as Int }

        fun nativeOwnersOpen(): Boolean = resources.all { (field(it, "closed") as Boolean).not() && field(it, "storage") != null }

        override fun close() {
            if (closed) return
            closed = true
            val closeBorrows = borrows.map { borrow -> { if ((field(borrow, "closed") as Boolean).not()) borrow.close() } }
            runCanvasTestCleanup(null, *(closeBorrows + terminalDevice::closeAfterGuiDiscarded).toTypedArray())
        }

        private fun field(
            owner: Any,
            name: String,
        ): Any? =
            owner.javaClass.getDeclaredField(name).let { field ->
                check(field.trySetAccessible())
                field.get(owner)
            }

        private fun method(
            owner: Class<*>,
            name: String,
            firstParameter: Class<*>? = null,
        ): Method =
            owner.declaredMethods.single {
                it.name.substringBefore('$') == name &&
                    (firstParameter == null || it.parameterTypes.firstOrNull() == firstParameter)
            }

        @Suppress("StringLiteralComparison") // Exact reflected JVM method names are decoded at this native adapter boundary.
        private fun supportMethod(): Method {
            val helpers = listOf("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftSampledTextureLimitsKt", "dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftPortableTextureFactoryKt")
            for (name in helpers) {
                val helper =
                    try {
                        Class.forName(name)
                    } catch (_: ClassNotFoundException) {
                        continue
                    }
                helper.declaredMethods.singleOrNull { it.name.substringBefore('$') == "supportsFabricMinecraftSampledImage" }?.let { return it }
            }
            error("The actual runtime archive has no sampled-image native extent preflight.")
        }

        private inline fun original(action: () -> Any?): Any? =
            try {
                action()
            } catch (failure: InvocationTargetException) {
                throw failure.cause ?: failure
            }
    }

    /**
     * Delays completion observations of real native fences until this independent manager's terminal shutdown.
     */
    private class GatedDriver(
        private val actual: NativeCanvasDriver,
    ) : NativeCanvasDriver by actual {
        val fences = ArrayList<Gate>()

        override fun fence(): NativeCanvasFence = Gate(actual.fence()).also(fences::add)

        override fun finish() {
            actual.finish()
            fences.forEach { it.open = true }
        }
    }

    /**
     * Owns one actual GPU fence and records its release, without submitting or waiting during polling.
     */
    private class Gate(
        private val actual: NativeCanvasFence,
    ) : NativeCanvasFence {
        var open = false
        var closed = false

        override fun isSignalled(): Boolean = open && actual.isSignalled()

        override fun close() {
            check(closed.not())
            actual.close()
            closed = true
        }
    }
}
