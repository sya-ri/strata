package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Verifies bounded legacy texture envelopes without loading Minecraft or changing fractional image sampling.
 */
internal class FabricMinecraftSampledBoundsTest {
    @Test
    fun ordinaryFractionalAndIntegralDestinationsKeepTheirTightEnvelopes() {
        val viewport = IntRect(0, 0, 320, 240)

        assertEquals(IntRect(2, 3, 9, 10), FloatRect(2.25f, 3.5f, 8.75f, 9.125f).enclosingFabricViewportBounds(viewport))
        assertEquals(IntRect(2, 3, 8, 9), FloatRect(2f, 3f, 8f, 9f).enclosingFabricViewportBounds(viewport))
        assertEquals(IntRect(0, 2, 6, 240), FloatRect(-0.75f, 2.25f, 5.125f, 241.5f).enclosingFabricViewportBounds(viewport))
        assertEquals(IntRect(0, 0, 1, 1), FloatRect(-0.75f, -1.25f, 0.125f, 0.75f).enclosingFabricViewportBounds(viewport))
        assertEquals(IntRect(0, 0, 1, 1), FloatRect(0f, 0f, Float.MIN_VALUE, Float.MIN_VALUE).enclosingFabricViewportBounds(viewport))
    }

    @Test
    fun validSampledCommandsMaySpanTheIntegerRangeOnEitherAxis() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val source = FloatRect(0f, 0f, 1f, 1f)
        val viewport = IntRect(0, 0, 320, 240)
        val horizontal = DrawCommand.SampledImage(image, source, FloatRect(-2147483648f, 0f, 2147483648f, 1f))
        val vertical = DrawCommand.SampledImage(image, source, FloatRect(0f, -2147483648f, 1f, 2147483648f))

        assertEquals(IntRect(0, 0, 320, 1), horizontal.destination.enclosingFabricViewportBounds(viewport))
        assertEquals(IntRect(0, 0, 1, 240), vertical.destination.enclosingFabricViewportBounds(viewport))
    }

    @Test
    fun extremeFiniteDestinationsStayWithinTheViewportBeforeIntegerExtentChecks() {
        val destination = FloatRect(-Float.MAX_VALUE / 2f, -Float.MAX_VALUE / 2f, Float.MAX_VALUE / 2f, Float.MAX_VALUE / 2f)
        val maximumViewport = IntRect(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)
        val narrowMaximumViewport = IntRect(Int.MAX_VALUE - 10, Int.MAX_VALUE - 12, Int.MAX_VALUE, Int.MAX_VALUE)
        val offsetViewport = IntRect(-10, -20, 10, 20)

        assertEquals(maximumViewport, destination.enclosingFabricViewportBounds(maximumViewport))
        assertEquals(narrowMaximumViewport, destination.enclosingFabricViewportBounds(narrowMaximumViewport))
        assertEquals(offsetViewport, destination.enclosingFabricViewportBounds(offsetViewport))
        assertNull(destination.enclosingFabricViewportBounds(IntRect(0, 0, 0, 0)))
    }

    @Test
    fun fullyOffscreenDestinationsAndEdgeContactHaveNoVisibleEnvelope() {
        val viewport = IntRect(0, 0, 320, 240)
        val destinations =
            listOf(
                FloatRect(Float.MAX_VALUE / 2f, 0f, Float.MAX_VALUE, 1f),
                FloatRect(-Float.MAX_VALUE, 0f, -Float.MAX_VALUE / 2f, 1f),
                FloatRect(0f, Float.MAX_VALUE / 2f, 1f, Float.MAX_VALUE),
                FloatRect(0f, -Float.MAX_VALUE, 1f, -Float.MAX_VALUE / 2f),
                FloatRect(320f, 1f, 321f, 2f),
                FloatRect(-1f, 1f, 0f, 2f),
                FloatRect(1f, 240f, 2f, 241f),
                FloatRect(1f, -1f, 2f, 0f),
            )

        destinations.forEach { destination -> assertNull(destination.enclosingFabricViewportBounds(viewport)) }
    }

    @Test
    fun clippedTextureEnvelopePreservesTheOriginalSourceMappingAfterFloatLocalization() {
        val image = createDrawImage(IntSize(4, 2), IntArray(8) { index -> 0xFF101010.toInt() + index * 0x101010 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 4f, 2f), FloatRect(-2147483648f, -0.75f, 2147483648f, 9.25f))
        val clip = IntRect(7, 3, 13, 7)
        val bounds = checkNotNull(command.destination.enclosingFabricViewportBounds(clip))
        val offset = IntOffset(-bounds.left, -bounds.top)
        val overlay = DrawCommand.FillRectangle(IntRect(9, 4, 11, 6), ArgbColor(0x8000FF00.toInt()))
        val commands = listOf(DrawCommand.PushClip(clip), command, overlay, DrawCommand.PopClip)
        val localized =
            listOf(
                DrawCommand.PushClip(clip + offset),
                command.copy(destination = command.destination + offset),
                overlay.copy(bounds = overlay.bounds + offset),
                DrawCommand.PopClip,
            )

        assertEquals(clip, bounds)
        for (scale in 1..3) {
            val full = rasterizeHeadless(commands, IntSize(24, 12), scale)
            val cropped = rasterizeHeadless(localized, bounds.size, scale)
            val expected =
                IntArray(cropped.size.width * cropped.size.height) { index ->
                    full.argbAt(bounds.left * scale + index % cropped.size.width, bounds.top * scale + index / cropped.size.width)
                }
            assertArrayEquals(expected, cropped.copyArgb())
        }
    }
}
