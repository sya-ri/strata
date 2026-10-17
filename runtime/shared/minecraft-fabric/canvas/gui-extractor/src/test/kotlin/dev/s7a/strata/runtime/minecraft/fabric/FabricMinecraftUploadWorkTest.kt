@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Distinguishes transferred metadata from source/raster pixels without a loaded game or native allocation.
 */
internal class FabricMinecraftUploadWorkTest {
    @Test
    fun sourceAndScaledRasterUploadsHaveIndependentCountsAndPayloads() {
        val work = FabricMinecraftUploadWork()
        val source = createDrawImage(IntSize(3, 2), IntArray(6))
        val raster = FabricMinecraftPortableImage(listOf(DrawCommand.FillRectangle(IntRect(0, 0, 7, 5), ArgbColor(-1))), IntSize(7, 5), 3)
        work.source(source)
        work.portable(raster)
        work.portable(raster)
        assertEquals(24L, counter(work, "sourceUploadByteCount"))
        assertEquals(2L, counter(work, "rasterUploadCount"))
        assertEquals(2L * 7 * 5 * 3 * 3 * 4, counter(work, "rasterUploadByteCount"))
        assertEquals(0L, counter(work, "samplingUploadCount"))
        assertEquals(0L, counter(work, "samplingUploadByteCount"))
    }

    @Test
    fun samplingCountsOnlyItsThreeMetadataRowsRatherThanTheGeneratedOutput() {
        val work = FabricMinecraftUploadWork()
        val command = command()
        val size = IntSize(64, 64)
        val sampling = FabricMinecraftSamplingMap(command, IntRect(0, 0, 64, 64), 1)
        work.portable(FabricMinecraftPortableImage(listOf(command), size, 1, sampling = sampling))
        assertEquals(1L, counter(work, "samplingUploadCount"))
        assertEquals(64L * 3 * 4, counter(work, "samplingUploadByteCount"))
        assertEquals(0L, counter(work, "rasterUploadCount"))
        assertEquals(0L, counter(work, "rasterUploadByteCount"))
        assertEquals(0L, counter(work, "sourceUploadByteCount"))
    }

    @Test
    fun compositionCountsOneSuccessfulLayerGroupIncludingBothMetadataTextures() {
        val work = FabricMinecraftUploadWork()
        val size = IntSize(64, 64)
        val commands = listOf(DrawCommand.FillRectangle(IntRect(0, 0, 64, 64), ArgbColor(-1)), command())
        val composition = checkNotNull(FabricMinecraftCompositionMap.create(commands, size, 1, IntOffset.Zero, FabricMinecraftSamplingBudget()))
        work.portable(FabricMinecraftPortableImage(commands, size, 1, composition = composition))
        assertEquals(1L, counter(work, "samplingUploadCount"))
        assertEquals((64L * 6 + 1536) * 4, counter(work, "samplingUploadByteCount"))
        assertEquals(0L, counter(work, "rasterUploadCount"))
        assertEquals(0L, counter(work, "sourceUploadByteCount"))
        assertEquals(0L, counter(FabricMinecraftUploadWork(), "samplingUploadCount"))
        assertTrue(FabricMinecraftUploadWork::class.java.declaredFields.all { it.type == Long::class.javaPrimitiveType })
    }

    private fun command(): DrawCommand.SampledImage =
        DrawCommand.SampledImage(
            createDrawImage(IntSize(2, 2), IntArray(4) { -1 }),
            FloatRect(0f, 0f, 2f, 2f),
            FloatRect(0f, 0f, 64f, 64f),
            alphaCutoff = 0f,
        )

    private fun counter(
        work: FabricMinecraftUploadWork,
        name: String,
    ): Long {
        val field = FabricMinecraftUploadWork::class.java.getDeclaredField(name)
        check(field.trySetAccessible())
        return field.getLong(work)
    }
}
