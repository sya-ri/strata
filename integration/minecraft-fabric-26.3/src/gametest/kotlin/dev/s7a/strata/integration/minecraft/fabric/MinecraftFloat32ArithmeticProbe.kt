package dev.s7a.strata.integration.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.GpuFormat
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Compares the production integer shader's binary32 operations with independent JVM Float results on the loaded backend.
 * The diagnostic renders bounded 64-by-64 pages and reads integer bit patterns, without a pixel tolerance or native blending.
 * All temporary native allocations remain owned until an explicitly submitted, bounded diagnostic fence completes.
 */
internal object MinecraftFloat32ArithmeticProbe {
    private val pipeline =
        RenderPipeline
            .builder()
            .withLocation(Identifier.fromNamespaceAndPath("strata", "pipeline/float32_probe"))
            .withVertexShader(Identifier.fromNamespaceAndPath("strata", "core/portable_composition"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("strata", "core/float32_probe"))
            .withBindGroupLayout(BindGroupLayout.builder().withUniform("InSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
            .withDepthStencilState(Optional.empty())
            .withColorTargetState(ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
            .withCull(false)
            .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
            .build()

    /**
     * Checks every byte-alpha pair, deterministic mantissas, rounding boundaries and exponent alignment, then writes a success receipt.
     * Must run synchronously on the render thread after shader resources have loaded; any mismatch aborts acceptance.
     */
    internal fun run(output: Path) {
        RenderSystem.assertOnRenderThread()
        val cases = cases()
        val device = RenderSystem.getDevice()
        Resources().use { resources ->
            val source = resources.own { device.createTexture({ "Strata binary32 oracle inputs" }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 192, 64, 1, 1) }
            val sourceView = resources.own { device.createTextureView(source) }
            val target = resources.own { device.createTexture({ "Strata binary32 oracle results" }, GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_SRC, GpuFormat.RGBA8_UNORM, 64, 64, 1, 1) }
            val targetView = resources.own { device.createTextureView(target) }
            val buffer = resources.own { device.createBuffer({ "Strata binary32 oracle readback" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, 64L * 64 * 4) }
            val compiled = RenderSystem.getCompiledPipeline(pipeline)
            val nearest = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST)
            for (start in cases.indices step 4096) {
                NativeImage(192, 64, false).use { image ->
                    for (index in 0 until 4096) {
                        val case = cases.getOrNull(start + index) ?: Case(Operation.Add, 0f, 0f)
                        val x = index % 64 * 3
                        val y = index / 64
                        image.setPixel(x, y, rgbaWord(case.operation.ordinal))
                        image.setPixel(x + 1, y, rgbaWord(case.a.toRawBits()))
                        image.setPixel(x + 2, y, rgbaWord(case.b.toRawBits()))
                    }
                    val encoder = device.createCommandEncoder()
                    encoder.writeToTexture(source, image)
                    encoder.createRenderPass({ "Strata binary32 exact arithmetic probe" }, targetView, Optional.empty()).use { pass ->
                        pass.setPipeline(compiled)
                        pass.setUniform("InSampler", sourceView, nearest)
                        pass.draw(3, 1, 0, 0)
                    }
                    encoder.copyTextureToBuffer(target, buffer, 0L, {}, 0)
                    awaitDiagnosticWork()
                    buffer.map(true, false).use { mapped -> compare(cases, start, mapped.data().order(ByteOrder.LITTLE_ENDIAN)) }
                }
            }
        }
        Files.createDirectories(output)
        Files.writeString(output.resolve("float32-arithmetic.properties"), "cases=${cases.size}\ntolerance=0\nresult=passed\n")
    }

    private fun compare(
        cases: List<Case>,
        start: Int,
        bytes: ByteBuffer,
    ) {
        for (index in 0 until minOf(4096, cases.size - start)) {
            val case = cases[start + index]
            val actual = bytes.getInt(index * 4)
            val expected = case.expected()
            check(actual == expected) { "Binary32 ${case.operation} mismatch at ${start + index}: a=${case.a.toRawBits().toUInt().toString(16)}, b=${case.b.toRawBits().toUInt().toString(16)}, expected=${expected.toUInt().toString(16)}, actual=${actual.toUInt().toString(16)}" }
        }
    }

    private fun cases(): List<Case> =
        buildList {
            for (source in 0..255) {
                for (destination in 0..255) {
                    val a = source.toFloat() / 255f
                    val b = destination.toFloat() / 255f
                    val alpha = a * b
                    val weight = b * (1f - alpha)
                    add(Case(Operation.Multiply, a, b))
                    add(Case(Operation.Multiply, alpha, weight))
                    add(Case(Operation.Add, alpha, weight))
                    add(Case(Operation.Divide, alpha, if (alpha + weight == 0f) 1f else alpha + weight))
                    add(Case(Operation.Quantize, a, 0f))
                }
            }
            for (byte in 0..254) {
                val boundary = (byte.toFloat() + 0.5f) / 255f
                for (value in listOf(Math.nextDown(boundary), boundary, Math.nextUp(boundary))) add(Case(Operation.Quantize, value, 0f))
            }
            val random = Random(73471)
            repeat(16384) {
                val a = Float.fromBits(random.nextInt(95, 128) shl 23 or random.nextInt(0x800000))
                val b = Float.fromBits(random.nextInt(95, 128) shl 23 or random.nextInt(0x800000))
                add(Case(Operation.Add, a, b))
                add(Case(Operation.Multiply, a, b))
                add(Case(Operation.Divide, minOf(a, b), maxOf(a, b)))
            }
            for (distance in listOf(0, 1, 23, 24, 25, 31, 32, 33, 64)) {
                add(Case(Operation.Add, 1f, Float.fromBits((127 - distance) shl 23)))
                add(Case(Operation.Add, Math.nextUp(1f), Float.fromBits((127 - distance) shl 23)))
            }
        }

    // NativeImage.setPixel accepts ARGB; arrange the resulting RGBA bytes as a little-endian uint word.
    private fun rgbaWord(value: Int): Int = ((value and 255) shl 16) or ((value ushr 8 and 255) shl 8) or (value ushr 16 and 255) or (value ushr 24 shl 24)

    private fun awaitDiagnosticWork() {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.createFence().use { fence ->
            encoder.submit()
            check(fence.awaitCompletion(5_000_000_000L)) { "Binary32 arithmetic probe exceeded its bounded native completion timeout." }
        }
    }

    private enum class Operation { Add, Multiply, Divide, Quantize }

    private class Case(
        val operation: Operation,
        val a: Float,
        val b: Float,
    ) {
        fun expected(): Int =
            when (operation) {
                Operation.Add -> (a + b).toRawBits()
                Operation.Multiply -> (a * b).toRawBits()
                Operation.Divide -> (a / b).toRawBits()
                Operation.Quantize -> (a * 255f).roundToInt().coerceIn(0, 255)
            }
    }

    private class Resources : AutoCloseable {
        private val native = ArrayList<AutoCloseable>(5)

        fun <T : AutoCloseable> own(create: () -> T): T = create().also(native::add)

        override fun close() {
            runCanvasTestCleanup(null, ::awaitDiagnosticWork, *native.asReversed().map { { it.close() } }.toTypedArray())
        }
    }
}
