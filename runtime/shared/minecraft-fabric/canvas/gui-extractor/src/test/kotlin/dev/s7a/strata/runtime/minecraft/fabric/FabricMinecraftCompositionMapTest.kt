@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/**
 * Applies uploaded command words using independent CPU arithmetic, then compares the complete tile with the existing headless oracle.
 * Checks original-coordinate sampling, every byte-alpha pair, metadata ownership keys and whole-tile admission and availability fallback.
 */
internal class FabricMinecraftCompositionMapTest {
    @Test
    fun everySourceAndDestinationAlphaMatchesThroughArbitraryTintAndCutoff() {
        val size = IntSize(256, 256)
        val bounds = IntRect(0, 0, 256, 256)
        val image = createDrawImage(size) { x, y -> (x shl 24) or ((x * 73471 + y * 1337) and 0xFFFFFF) }
        val destination = createDrawImage(size) { x, y -> (y shl 24) or ((y * 7919 + x * 1337) and 0xFFFFFF) }
        for (tint in listOf(0, -1, 0x017FC1E3, 0x80BFD7EF.toInt(), 0xFE7FC1E3.toInt())) {
            val boundary = (128f / 255f) * ((tint ushr 24).toFloat() / 255f)
            for (cutoff in listOf(0f, boundary, Math.nextUp(boundary).coerceAtMost(1f), 1f)) {
                val commands = listOf(DrawCommand.BlitImagePixels(destination, bounds, bounds), DrawCommand.SampledImage(image, FloatRect(0f, 0f, 256f, 256f), FloatRect(0f, 0f, 256f, 256f), ArgbColor(tint), alphaCutoff = cutoff))
                compare(commands, size, 1, IntOffset.Zero)
            }
        }
    }

    @Test
    fun orderedIntegerBlitsMirroredFractionalCropsAndNestedClipsRetainOriginalCoordinates() {
        val image = createDrawImage(IntSize(17, 11)) { x, y -> (((x * 19 + y * 17) and 255) shl 24) or ((x * 73471 + y * 1337) and 0xFFFFFF) }
        val origin = IntOffset(179, 69)
        val size = IntSize(64, 64)
        val whole = IntRect(179, 69, 243, 133)
        for (scale in 1..4) {
            for (orientation in SampledImageOrientation.entries) {
                val commands =
                    listOf(
                        DrawCommand.FillRectangle(whole, ArgbColor(0x40213759)),
                        DrawCommand.BlitImage(image, IntRect(1, 2, 16, 10), IntRect(180, 70, 239, 131)),
                        DrawCommand.PushClip(IntRect(182, 71, 240, 129)),
                        DrawCommand.PushFractionalClip(FloatRect(183.125f, 72.375f, 239.25f, 128.5f)),
                        DrawCommand.SampledImage(image, FloatRect(0.125f, 0.375f, 16.875f, 10.5f), FloatRect(181.25f, 70.25f, 240.75f, 131.75f), ArgbColor(0x80BFD7EF.toInt()), alphaCutoff = 0.1f, orientation = orientation),
                        DrawCommand.BlitImagePixels(image, IntRect(0, 0, 17, 11), IntRect(181, 70, 242, 132)),
                        DrawCommand.PopClip,
                        DrawCommand.PopClip,
                        DrawCommand.SampledImage(image, FloatRect(0.25f, 0.125f, 16.5f, 10.75f), FloatRect(185.375f, 76.125f, 236.75f, 125.25f), ArgbColor(0xC037659B.toInt()), alphaCutoff = 0f, orientation = orientation),
                    )
                compare(commands, size, scale, origin)
            }
        }
    }

    @Test
    fun capacityRejectsCompleteTilesAndIncludesExistingOutputs() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 4096f, 4096f), alphaCutoff = 0f)
        val small = IntSize(64, 64)
        val budget = FabricMinecraftSamplingBudget()
        val map = { size: IntSize -> FabricMinecraftCompositionMap.create(listOf(command), size, 1, IntOffset.Zero, budget) }
        assertNull(map(IntSize(63, 64)))
        assertNull(map(IntSize(4097, 1)))
        assertNull(map(IntSize(4096, 4096)))
        assertNotNull(map(IntSize(4096, 1)))
        repeat(255) { assertNotNull(map(small)) }
        assertNull(map(small))
        val passes = FabricMinecraftSamplingBudget()
        assertNotNull(FabricMinecraftCompositionMap.create(List(1024) { command }, small, 1, IntOffset.Zero, passes))
        assertNull(FabricMinecraftCompositionMap.create(listOf(command), small, 1, IntOffset.Zero, passes))
        assertNull(FabricMinecraftCompositionMap.create(List(1025) { command }, small, 1, IntOffset.Zero, FabricMinecraftSamplingBudget()))
    }

    @Test
    fun changedImageIdentityAndUnavailableSourcesCannotReuseGpuPixels() {
        val size = IntSize(64, 64)
        val image = createDrawImage(IntSize(1, 1), intArrayOf(0x8088AACC.toInt()))
        val replacement = createDrawImage(image.size, intArrayOf(0x8088AACC.toInt()))
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 64f, 64f), ArgbColor(0x80BFD7EF.toInt()), alphaCutoff = 0f)
        val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(command), size), 1, compositionEnabled = true)
        assertNotNull(inputs.portable.single().composition)
        assertEquals(listOf(image), inputs.sampled)
        assertEquals(0L, inputs.tintFallbackImages)
        assertSame(inputs, inputs.resolve({ true }) { error("No direct source fallback") })
        val unavailable = inputs.resolve({ false }) { true }
        assertNull(unavailable.portable.single().composition)
        assertEquals(1L, unavailable.tintFallbackImages)
        assertArrayEquals(
            inputs.portable
                .single()
                .rasterize()
                .copyArgb(),
            unavailable.portable
                .single()
                .rasterize()
                .copyArgb(),
        )
        val changed = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(listOf(command.copy(image = replacement)), size), 1, compositionEnabled = true)
        assertFalse(inputs.portable.single().equivalent(changed.portable.single()))
        assertTrue(inputs.portable.single().equivalent(inputs.portable.single()))
    }

    private fun compare(
        commands: List<DrawCommand>,
        size: IntSize,
        scale: Int,
        origin: IntOffset,
    ) {
        val map = checkNotNull(FabricMinecraftCompositionMap.create(commands, size, scale, origin, FabricMinecraftSamplingBudget()))
        val bounds = IntRect(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
        assertArrayEquals(rasterizeHeadlessRegion(commands, bounds, scale).copyArgb(), compose(map), "scale=$scale origin=$origin commands=$commands")
    }

    private fun compose(map: FabricMinecraftCompositionMap): IntArray {
        val pixels = IntArray(map.physicalSize.width * map.physicalSize.height)
        map.sources.forEachIndexed { pass, source ->
            val row = pass * 3
            val kind = Kind.entries[word(map.indices, 0, row + 2)]
            for (y in 0 until map.physicalSize.height) {
                val sy = word(map.indices, y, row + 1) - 1
                if (sy < 0) continue
                for (x in 0 until map.physicalSize.width) {
                    val sx = word(map.indices, x, row) - 1
                    if (sx < 0) continue
                    val argb = if (kind == Kind.Fill) word(map.indices, 1, row + 2) else checkNotNull(source).argbAt(sx, sy)
                    val position = y * map.physicalSize.width + x
                    pixels[position] = if (kind == Kind.Sampled) sampled(map, row, argb, pixels[position]) else integer(argb, pixels[position])
                }
            }
        }
        return pixels
    }

    private fun sampled(
        map: FabricMinecraftCompositionMap,
        row: Int,
        source: Int,
        destination: Int,
    ): Int {
        val tint = word(map.indices, 3, row + 2)
        val alpha = factor(map, source ushr 24, 1, tint)
        if (alpha == 0f || alpha < Float.fromBits(word(map.indices, 2, row + 2))) return destination
        val weight = factor(map, destination ushr 24, 0, tint) * factor(map, source ushr 24, 2, tint)
        val outputAlpha = alpha + weight
        val alphaByte = (outputAlpha * 255f).roundToInt().coerceIn(0, 255)
        if (alphaByte == 0) return 0
        var result = alphaByte shl 24
        for (channel in 0..2) {
            val shift = (2 - channel) * 8
            val contribution = factor(map, source ushr shift and 255, channel + 3, tint) * alpha
            val background = factor(map, destination ushr shift and 255, 0, tint) * weight
            val byte = ((contribution + background) / outputAlpha * 255f).roundToInt().coerceIn(0, 255)
            result = result or (byte shl shift)
        }
        return result
    }

    private fun integer(
        source: Int,
        destination: Int,
    ): Int {
        val a = source ushr 24
        val d = destination ushr 24
        if (a == 255) return source
        if (a == 0) return if (d == 0) 0 else destination
        if (d == 0) return source
        val denominator = a * 255 + d * (255 - a)
        var result = ((denominator + 127) / 255) shl 24
        for (shift in listOf(16, 8, 0)) {
            val numerator = (source ushr shift and 255) * a * 255 + (destination ushr shift and 255) * d * (255 - a)
            result = result or ((numerator + denominator / 2) / denominator shl shift)
        }
        return result
    }

    private fun factor(
        map: FabricMinecraftCompositionMap,
        byte: Int,
        component: Int,
        tint: Int,
    ): Float = Float.fromBits(word(map.factors, component * 256 + byte, tint))

    private fun word(
        image: DrawImage,
        x: Int,
        y: Int,
    ): Int {
        val argb = image.argbAt(x, y)
        return (argb ushr 16 and 255) or ((argb ushr 8 and 255) shl 8) or ((argb and 255) shl 16) or (argb and 0xFF000000.toInt())
    }

    private enum class Kind { Fill, IntegerImage, Sampled }
}
