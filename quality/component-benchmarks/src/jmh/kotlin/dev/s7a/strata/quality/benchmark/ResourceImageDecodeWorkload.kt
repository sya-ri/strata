package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.SlotBinding
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.MinecraftInventorySlotBinding
import dev.s7a.strata.runtime.minecraft.MinecraftPlayerSkinBinding
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiPlatform
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO

/**
 * Uses normal packaged NativeImage and the actual optional decoder helper, never copied cache algorithms or loaders.
 * PNG preparation is deterministic and untimed; the complete protocol includes decoder and host ownership and release.
 * Baseline archives use their original NativeImage.read/copy/createDrawImage sequence when the new helper is absent.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ResourceImageDecodeWorkload(
    private val case: ResourceImageDecodeBenchmark.Case,
    private val pattern: ResourceImageDecodeBenchmark.Pattern,
    private val verify: Boolean = false,
) : AutoCloseable {
    private val profile = ComponentProfile.create()
    private val inputs = List(8) { index -> input(case.extent, index, case.flat) }
    private val priming = List(2) { index -> input(if (0 < case.byteImages) 1024 else 1, index + 32, flat = false) }
    private val loader = javaClass.classLoader
    private val nativeType = Class.forName("com.mojang.blaze3d.platform.NativeImage")
    private val nativeRead = nativeType.getMethod("read", InputStream::class.java)
    private val nativeClose = nativeType.getMethod("close")
    private val nativeWidth = nativeType.getMethod("getWidth")
    private val nativeHeight = nativeType.getMethod("getHeight")
    private val nativePixels =
        Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftNativeImageBridgeKt")
            .declaredMethods
            .single { it.parameterCount == 1 && it.parameterTypes[0] == nativeType && it.returnType == IntArray::class.java }
            .apply { isAccessible = true }
    private val cacheType = optionalCache()
    private val decodedConstructor =
        cacheType
            ?.declaredClasses
            ?.flatMap { it.constructors.toList() }
            ?.single { it.parameterTypes.contentEquals(arrayOf(IntSize::class.java, IntArray::class.java)) }
    private val loadMethod = cacheType?.getMethod("load", Any::class.java, Function0::class.java)
    private val closeMethod = cacheType?.getMethod("close", Any::class.java, Boolean::class.javaPrimitiveType)
    private var operation = 0
    private var expectedOpens = 0L
    private var actualOpens = 0L
    private var actualCloses = 0L
    private var nativeDecodes = 0L
    private var lastImage: DrawImage? = null
    private var lastExpected: IntArray? = null
    private val session = Session()

    init {
        listOfNotNull(nativeType, cacheType).forEach { type ->
            check(type.classLoader === loader) { "Image fixtures require ordinary application-loader classes." }
            check(Files.isRegularFile(Path.of(type.protectionDomain.codeSource.location.toURI()))) { "Image fixtures require actual packaged archives." }
        }
        if (case == ResourceImageDecodeBenchmark.Case.EncodedOverflow) check(8 * 1024 * 1024 < inputs.first().encoded.size)
        if (case == ResourceImageDecodeBenchmark.Case.PayloadOverflow) check(16L * 1024 * 1024 <= inputs.first().pixels.size.toLong() * Int.SIZE_BYTES)
        resolve()
    }

    /**
     * Reads one direct source or reevaluates the actual retained public host on its normal revision boundary.
     */
    fun resolve(): DrawImage {
        val selected = selected(operation++)
        val image = session.resolve(selected)
        if (verify) {
            val expected = session.expected(selected)
            check(image.size == IntSize(case.extent, case.extent) && image.copyArgb().contentEquals(expected))
            val previous = lastImage
            val oldExpected = lastExpected
            if (previous != null && oldExpected != null) check(previous.copyArgb().contentEquals(oldExpected))
            if (session.isPinned(selected)) {
                if (previous != null && pattern != ResourceImageDecodeBenchmark.Pattern.Cold) check(image === previous)
            } else if (previous != null) {
                check(image !== previous)
            }
            lastImage = image
            lastExpected = expected
        }
        return image
    }

    /**
     * Performs a complete fresh acquisition and release instead of hiding cold lifetime work in invocation setup.
     */
    fun completeProtocol(): DrawImage =
        run {
            val before = nativeDecodes
            val image =
                Session().use { fresh ->
                    val selected = selected(operation++)
                    val resolved = fresh.resolve(selected)
                    if (verify) check(resolved.copyArgb().contentEquals(fresh.expected(selected)))
                    resolved
                }
            if (verify) check(nativeDecodes - before == case.identifiers + case.byteImages + 1L)
            image
        }

    /**
     * Checks sixteen primed operations against independently enumerated source-call and native-decode counts.
     */
    fun verifySteady() {
        check(verify)
        val opens = actualOpens
        val decodes = nativeDecodes
        repeat(16) { resolve() }
        val below =
            case == ResourceImageDecodeBenchmark.Case.IdentifiersBelow ||
                case == ResourceImageDecodeBenchmark.Case.BytesBelow
        val expectedCalls = if (below) (if (pattern == ResourceImageDecodeBenchmark.Pattern.Cold) 14L else 0L) else 16L
        check(actualOpens - opens == expectedCalls)
        val reusable = cacheType != null && case != ResourceImageDecodeBenchmark.Case.EncodedOverflow && case != ResourceImageDecodeBenchmark.Case.PayloadOverflow
        val expectedDecodes = if (reusable && pattern == ResourceImageDecodeBenchmark.Pattern.Hot) 0L else expectedCalls
        check(nativeDecodes - decodes == expectedDecodes)
        println("${case.name}.${pattern.name}: sourceOpens=$expectedCalls, nativeDecodes=$expectedDecodes, sourceCloses=${expectedCalls * 2}")
    }

    /**
     * Requires exact platform calls and both source closes, with no skipped current overflow resolution.
     */
    fun verify() {
        check(actualOpens == expectedOpens && actualCloses == actualOpens * 2)
    }

    override fun close() {
        session.close()
        if (verify) {
            verify()
            lastImage?.let { image -> check(image.copyArgb().contentEquals(checkNotNull(lastExpected))) }
        }
        lastImage = null
        lastExpected = null
    }

    private fun selected(index: Int): Selection =
        when (pattern) {
            ResourceImageDecodeBenchmark.Pattern.Hot -> Selection(0, 0)

            ResourceImageDecodeBenchmark.Pattern.Cold -> Selection(index % inputs.size, index % inputs.size)

            ResourceImageDecodeBenchmark.Pattern.Replacement -> Selection(0, index % 2)
        }

    private fun optionalCache(): Class<*>? =
        try {
            Class.forName("dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftImageDecodeCache")
        } catch (_: ClassNotFoundException) {
            null
        }

    private fun decoded(input: InputStream): Pixels {
        if (verify) nativeDecodes++
        val image = call(nativeRead, null, input)
        try {
            return Pixels(
                IntSize(call(nativeWidth, image) as Int, call(nativeHeight, image) as Int),
                call(nativePixels, null, image) as IntArray,
            )
        } finally {
            call(nativeClose, image)
        }
    }

    private fun call(
        method: Method,
        receiver: Any?,
        vararg arguments: Any?,
    ): Any =
        try {
            method.invoke(receiver, *arguments) ?: Unit
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }

    private inner class Session : AutoCloseable {
        private val manager = Any()
        private val decoder =
            cacheType?.getConstructor(Function1::class.java)?.newInstance(
                { stream: InputStream ->
                    val pixels = decoded(stream)
                    checkNotNull(decodedConstructor).newInstance(pixels.size, pixels.argb)
                },
            )
        private val revision = mutableStateOf(0)
        private val primes = List(case.identifiers + case.byteImages) { index -> ResourceId("strata_benchmark", "textures/prime_$index.png") }
        private val primeIndices = primes.withIndex().associate { it.value to it.index }
        private val ids = List(inputs.size) { index -> ResourceId("strata_benchmark", "textures/input_$index.png") }
        private val pinned = HashMap<ResourceId, Int>()
        private var selection = Selection(0, 0)
        private var host: MinecraftUiHost? = null
        private var closed = false

        init {
            if (primes.isNotEmpty()) {
                if (verify) expectedOpens += primes.size
                host =
                    createMinecraftUiHost(
                        UiDefinition("Fixed resource admission") {
                            val index = revision.value
                            Stack {
                                if (index == 0) {
                                    primes.forEach { id ->
                                        Image(ImageSource.Resource(id), size = IntSize(1, 1))
                                    }
                                } else {
                                    Image(ImageSource.Resource(ids[selection.id]), size = IntSize(1, 1))
                                }
                            }
                        },
                        profile,
                        Platform(),
                    ).also {
                        it.attach()
                        it.frame(IntSize(1, 1))
                    }
            }
        }

        fun resolve(selected: Selection): DrawImage {
            selection = selected
            val retained = host
            if (retained == null) {
                if (verify) expectedOpens++
                return load(inputs[selected.input])
            }
            val admitted = primes.size
            val id = ids[selected.id]
            if (verify && pinned.containsKey(id).not()) {
                expectedOpens++
                if (admitted + pinned.keys.count { it in ids } < minOf(512, if (0 < case.byteImages) 32 else 512)) {
                    pinned[id] = selected.input
                }
            }
            revision.value++
            val commands = retained.frame(IntSize(1, 1)).drawCommands.filterIsInstance<DrawCommand.SampledImage>()
            check(commands.size == 1)
            return commands.single().image
        }

        fun expected(selected: Selection): IntArray = inputs[pinned[ids[selected.id]] ?: selected.input].pixels

        fun isPinned(selected: Selection): Boolean = pinned.containsKey(ids[selected.id])

        private fun load(input: Encoded): DrawImage {
            if (verify) {
                actualOpens++
            }
            val open = {
                object : ByteArrayInputStream(input.encoded) {
                    override fun close() {
                        if (verify) actualCloses++
                        super.close()
                    }
                }
            }
            val cache = decoder
            return if (cache == null) {
                val pixels = open().use { stream -> decoded(stream) }
                createDrawImage(pixels.size, pixels.argb)
            } else {
                call(checkNotNull(loadMethod), cache, manager, open) as DrawImage
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            host?.close()
            host = null
            decoder?.let { cache ->
                call(checkNotNull(closeMethod), cache, manager, true)
                if (verify) {
                    val reference = cache.javaClass.getDeclaredField("current").apply { isAccessible = true }.get(cache) as AtomicReference<*>
                    val state = reference.get()
                    check(state.javaClass.getDeclaredField("entry").apply { isAccessible = true }.get(state) == null)
                }
            }
        }

        private inner class Platform : MinecraftUiPlatform {
            override fun inventorySlot(binding: SlotBinding): MinecraftInventorySlotBinding = error("No inventory Slot expected: $binding")

            override fun playerSkin(source: PlayerSkinSource): MinecraftPlayerSkinBinding = error("No player skin expected: $source")

            override fun image(resource: ResourceId): DrawImage {
                val prime = primeIndices[resource]
                return if (prime != null) {
                    load(priming[prime % priming.size])
                } else {
                    check(resource == ids[selection.id])
                    load(inputs[selection.input])
                }
            }

            override fun refresh() = Unit

            override fun close() = Unit
        }
    }

    private data class Selection(
        val id: Int,
        val input: Int,
    )

    private data class Pixels(
        val size: IntSize,
        val argb: IntArray,
    )

    private data class Encoded(
        val encoded: ByteArray,
        val pixels: IntArray,
    )

    private companion object {
        fun input(
            extent: Int,
            generation: Int,
            flat: Boolean,
        ): Encoded {
            var random = generation + 19873427
            val pixels =
                IntArray(extent * extent) {
                    random = random xor (random shl 13)
                    random = random xor (random ushr 17)
                    random = random xor (random shl 5)
                    if (flat) 0x7F234567 + generation else random
                }
            val image = BufferedImage(extent, extent, BufferedImage.TYPE_INT_ARGB)
            image.setRGB(0, 0, extent, extent, pixels, 0, extent)
            val bytes =
                ByteArrayOutputStream().use { output ->
                    check(ImageIO.write(image, "PNG", output))
                    output.toByteArray()
                }
            return Encoded(bytes, pixels)
        }
    }
}
