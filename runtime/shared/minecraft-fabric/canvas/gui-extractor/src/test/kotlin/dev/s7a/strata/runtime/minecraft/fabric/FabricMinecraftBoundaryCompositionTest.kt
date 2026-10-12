package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftFrameLayer.SourceRejection
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Compares complete RGBA after the real portable rasterizer quantizes each original run.
 * A software presentation of the original native-image barriers isolates grouping from device blending.
 * Loaded GUI parity remains an independent requirement; this control does not claim backend qualification.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftBoundaryCompositionTest {
    @Test
    fun transparentBinaryBarrierKeepsWidePartialRunsSeparatelyQuantized() {
        val size = IntSize(24, 16)
        val binary = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0, -1))
        val partial = createDrawImage(IntSize(1, 1), intArrayOf(0x7F4A1A8E))
        val barrier = DrawCommand.SampledImage(binary, FloatRect(0f, 0f, 2f, 2f), FloatRect(8f, 1f, 12f, 5f), alphaCutoff = 0f)
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 24, 16), ArgbColor(0xFF7195B3.toInt())),
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80472853.toInt())),
                barrier,
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80007BBC.toInt())),
                barrier.copy(image = partial, source = FloatRect(0f, 0f, 1f, 1f), destination = FloatRect(20f, 1f, 24f, 5f)),
            )
        val preserved =
            partitionFabricMinecraftFrameWithSourceAdmission(commands, size, 1, false, false) {
                if (it.image === partial) SourceRejection.Composition else null
            }
        val resolved = FabricMinecraftFrameInputs.prepare(commands, size, 1).resolve({ true }, { true }, { it === partial })
        assertArrayEquals(present(preserved, size), present(resolved.layers, size))
    }

    @Test
    fun disjointBackdropAndTransparentBinaryBarriersRetainOriginalRunQuantization() {
        val size = IntSize(24, 16)
        val binary = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0, -1))
        val partial = createDrawImage(IntSize(1, 1), intArrayOf(0x7F4A1A8E))
        val barrier = DrawCommand.SampledImage(binary, FloatRect(0f, 0f, 2f, 2f), FloatRect(8f, 1f, 12f, 5f), alphaCutoff = 0f)
        val backdrop = barrier.copy(image = createDrawImage(IntSize(1, 1), intArrayOf(-1)), source = FloatRect(0f, 0f, 1f, 1f), destination = FloatRect(0f, 0f, 1f, 1f))
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 24, 16), ArgbColor(0xFF7195B3.toInt())),
                backdrop,
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80472853.toInt())),
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80007BBC.toInt())),
                barrier,
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80472853.toInt())),
                barrier.copy(image = partial, source = FloatRect(0f, 0f, 1f, 1f), destination = FloatRect(20f, 1f, 24f, 5f)),
            )
        val preserved =
            partitionFabricMinecraftFrameWithSourceAdmission(commands, size, 1, false, false) {
                if (it.image === partial) SourceRejection.Composition else null
            }
        assertEquals(2, preserved.count { it is FabricMinecraftFrameLayer.Sampled })
        val resolved = FabricMinecraftFrameInputs.prepare(commands, size, 1).resolve({ true }, { true }, { it === partial })
        assertEquals(2, resolved.layers.count { it is FabricMinecraftFrameLayer.Sampled })
        assertArrayEquals(present(preserved, size), present(resolved.layers, size))
    }

    @Test
    fun disjointBackdropRetainsTheKnownTwoFillQuantizationWitness() {
        val size = IntSize(24, 16)
        val binary = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0, -1))
        val partial = createDrawImage(IntSize(1, 1), intArrayOf(0x7F4A1A8E))
        val barrier = DrawCommand.SampledImage(binary, FloatRect(0f, 0f, 2f, 2f), FloatRect(8f, 1f, 12f, 5f), alphaCutoff = 0f)
        val backdrop = barrier.copy(image = createDrawImage(IntSize(1, 1), intArrayOf(-1)), source = FloatRect(0f, 0f, 1f, 1f), destination = FloatRect(0f, 0f, 1f, 1f))
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 24, 16), ArgbColor(0xFF7195B3.toInt())),
                backdrop,
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80472853.toInt())),
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80007BBC.toInt())),
                barrier,
                barrier.copy(image = partial, source = FloatRect(0f, 0f, 1f, 1f), destination = FloatRect(20f, 1f, 24f, 5f)),
            )
        val preserved =
            partitionFabricMinecraftFrameWithSourceAdmission(commands, size, 1, false, false) {
                if (it.image === partial) SourceRejection.Composition else null
            }
        assertEquals(2, preserved.count { it is FabricMinecraftFrameLayer.Sampled })
        val resolved = FabricMinecraftFrameInputs.prepare(commands, size, 1).resolve({ true }, { true }, { it === partial })
        assertEquals(2, resolved.layers.count { it is FabricMinecraftFrameLayer.Sampled })
        val original = present(preserved, size)
        val fallback = present(resolved.layers, size)
        assertEquals(0xFF2E6C9F.toInt(), original[2 * size.width + 2])
        assertArrayEquals(original, fallback)
    }

    @Test
    fun cpuAdmissionClonePreservesTheCanonicalRejectedSourcePartition() {
        val size = IntSize(24, 16)
        val binary = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0, -1))
        val partial = createDrawImage(IntSize(1, 1), intArrayOf(0x7F4A1A8E))
        val barrier = DrawCommand.SampledImage(binary, FloatRect(0f, 0f, 2f, 2f), FloatRect(8f, 1f, 12f, 5f), alphaCutoff = 0f)
        val commands =
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 24, 16), ArgbColor(0xFF7195B3.toInt())),
                barrier,
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80472853.toInt())),
                barrier.copy(image = partial, source = FloatRect(0f, 0f, 1f, 1f), destination = FloatRect(2f, 2f, 10f, 10f)),
                DrawCommand.FillRectangle(IntRect(1, 1, 12, 12), ArgbColor(0x80007BBC.toInt())),
            )
        val original = FabricMinecraftFrameInputs.prepare(commands, size, 1, compositionEnabled = true)
        val gpu = original.resolve({ true }, { true }, { it === partial })
        val fallback = original.javaClass.getDeclaredField("sourceFallback")
        check(fallback.trySetAccessible())
        val admitted = checkNotNull(fallback.get(original)) as FabricMinecraftFrameInputs
        assertSame(gpu, admitted)
        val cpu = FabricMinecraftFrameInputs(admitted.layers, 1, compositionEnabled = false)
        val canonical = FabricMinecraftFrameInputs.prepare(commands, size, 1, compositionEnabled = false).resolve({ true }, { true }, { it === partial })
        assertEquals(2, original.layers.count { it is FabricMinecraftFrameLayer.Sampled })
        assertSame(admitted.layers, cpu.layers)

        fun trace(layers: List<FabricMinecraftFrameLayer>): List<List<Any?>> =
            layers.map { layer ->
                when (layer) {
                    is FabricMinecraftFrameLayer.Portable -> listOf(layer.commands, layer.bounds, layer.absoluteCoordinates, layer.ineligibleSampledImages, layer.capacitySampledImages)
                    is FabricMinecraftFrameLayer.Sampled -> listOf(layer.command, layer.clip, layer.visibleBounds)
                    is FabricMinecraftFrameLayer.Platform -> error("This independent scene has no platform command.")
                }
            }
        assertEquals(trace(canonical.layers), trace(cpu.layers))
        assertEquals(
            commands,
            cpu.layers.flatMap { layer ->
                when (layer) {
                    is FabricMinecraftFrameLayer.Portable -> layer.commands
                    is FabricMinecraftFrameLayer.Sampled -> listOf(layer.command)
                    is FabricMinecraftFrameLayer.Platform -> error("This independent scene has no platform command.")
                }
            },
        )
        assertEquals(1, cpu.layers.count { it is FabricMinecraftFrameLayer.Sampled })
        assertArrayEquals(present(canonical.layers, size), present(cpu.layers, size))
    }

    /**
     * Rasterizes each real prepared portable input once, then presents its immutable RGBA image at the original boundary.
     * Native sampled commands retain their exact source, tint, cutoff, axis and active clip in the software comparison.
     */
    private fun present(
        layers: List<FabricMinecraftFrameLayer>,
        size: IntSize,
    ): IntArray {
        val inputs = FabricMinecraftFrameInputs(layers, 1)
        val presentation = ArrayList<DrawCommand>()
        var portableIndex = 0
        layers.forEach { layer ->
            when (layer) {
                is FabricMinecraftFrameLayer.Portable -> {
                    val raster = inputs.portable[portableIndex++].rasterize()
                    val image = createDrawImage(raster.size, raster.copyArgb())
                    presentation.add(DrawCommand.BlitImage(image, IntRect(0, 0, raster.size.width, raster.size.height), layer.bounds))
                }

                is FabricMinecraftFrameLayer.Sampled -> {
                    if (layer.sampling != null) portableIndex += 1
                    layer.clip?.let { presentation.add(DrawCommand.PushClip(it)) }
                    presentation.add(layer.command)
                    if (layer.clip != null) presentation.add(DrawCommand.PopClip)
                }

                is FabricMinecraftFrameLayer.Platform -> {
                    error("This independent RGBA scene contains no platform payload.")
                }
            }
        }
        return rasterizeHeadless(presentation, size).copyArgb()
    }
}
