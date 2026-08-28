@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks actual-origin line selection and bounded glyph submission against full clipped layout painting.
 *
 * Every layout detaches its pixels before the renderer closes; all comparisons run without game classes or native libraries.
 */
internal class MinecraftTextLayoutVisibilityTest {
    @Test
    fun nonzeroOriginsKeepStartMiddleAndEndPaintBoundedForLegacyAndBothSampledShadowOrders() {
        val value = List(101) { "A".repeat(128) }.joinToString("\n")
        val viewport = IntRect(5, 7, 29, 25)
        val glyph = MinecraftFontGlyph(3f, -0.25f, -0.25f, 2.5f, 8.5f, sampledImage(), shadowOffset = 1.25f)
        val cases =
            listOf(
                SampledCase(IntOffset.Zero, 2, 9, 34),
                SampledCase(IntOffset(120, 550), 2, 10, 36),
                SampledCase(IntOffset(360, 1100), 1, 9, 17),
            )
        for (interleaved in listOf(false, true)) {
            val result = sampledRenderer(mapOf('A'.code to glyph), interleaved).use { layout(it, value, 2) }
            for (case in cases) {
                val origin = IntOffset(5 - case.scroll.x, 7 - case.scroll.y)
                val capture = assertClippedParity(result, origin, viewport)
                val passes = if (interleaved) 1 else 2
                assertEquals(case.rows * (1 + case.candidatesPerLine * passes), capture.visits)
                assertEquals(case.commands, capture.commands.size)
                assertTrue(capture.visits <= 42)
            }
        }
        val legacy = legacyRenderer().use { layout(it, value, 2) }
        val legacyCounts = listOf(16 to 32, 20 to 40, 10 to 20)
        for ((case, expected) in cases.zip(legacyCounts)) {
            val origin = IntOffset(5 - case.scroll.x, 7 - case.scroll.y)
            val capture = assertClippedParity(legacy, origin, viewport)
            assertEquals(expected.first, capture.visits)
            assertEquals(expected.second, capture.commands.size)
        }
    }

    @Test
    fun oneExtremeOverhangDoesNotSubmitTheThousandsOfOrdinaryRowsInItsCandidateEnvelope() {
        val normal = MinecraftFontGlyph(3f, 0f, 0f, 2f, 8f, sampledImage())
        val glyphs = mapOf('A'.code to normal, 'B'.code to normal.copy(bottom = 90_009f))
        val result = sampledRenderer(glyphs).use { layout(it, "B\n" + "A\n".repeat(9999) + "A") }
        val origin = IntOffset(5, 7 - 45_000)
        val viewport = IntRect(5, 7, 29, 25)
        assertEquals(10_001, result.lines.size)
        assertEquals(5002, result.visibleLines(45_000.0, 45_018.0, origin.y).count())
        val capture = assertClippedParity(result, origin, viewport)
        assertEquals(6, capture.visits)
        assertEquals(6, capture.commands.size)
        val glyphCommands = capture.commands.filterIsInstance<DrawCommand.SampledImage>()
        assertEquals(2, glyphCommands.count { it.destination.height == 90_009f })
        assertEquals(4, glyphCommands.count { it.destination.height == 8f })
    }

    @Test
    fun aggregateCandidateIndexEvaluatesTheActualIntegerOriginBeforeFloatBearingCancellation() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val glyph = MinecraftFontGlyph(2f, 0f, -33_554_436f, 1f, -33_554_432f, image)
        val spacing = 33_554_422
        val result = sampledRenderer(mapOf('A'.code to glyph)).use { layout(it, "A\nA", spacing) }
        val origin = IntOffset(4, 4)
        val viewport = IntRect(4, 4, 12, 13)
        assertEquals(33_554_431, result.lineStep)
        assertEquals(-1.0, checkNotNull(result.lines[1].inkBounds).bottom + result.lineStep)
        assertEquals(1..1, result.visibleLines(0.0, 9.0, origin.y))
        val capture = assertClippedParity(result, origin, viewport)
        assertEquals(2, capture.visits)
        val shadow = capture.commands.single() as DrawCommand.SampledImage
        assertEquals(FloatRect(5f, 1f, 6f, 5f), shadow.destination)
        assertEquals(FloatRect(0.01f, 0.01f, 1.99f, 1.99f), shadow.source)
        assertEquals(0xFF3F3F3F.toInt(), rasterizeHeadless(capture.commands, IntSize(16, 16)).argbAt(5, 4))
    }

    private fun assertClippedParity(
        layout: MinecraftTextLayout,
        origin: IntOffset,
        viewport: IntRect,
    ): Capture {
        val full = capture(layout, origin)
        val visible = capture(layout, origin, viewport)
        assertEquals(full.commands.filter { intersects(it, viewport) }, visible.commands)
        val reference = listOf(DrawCommand.PushClip(viewport)) + full.commands + DrawCommand.PopClip
        val actual = listOf(DrawCommand.PushClip(viewport)) + visible.commands + DrawCommand.PopClip
        for (scale in 1..3) {
            assertArrayEquals(rasterizeHeadless(reference, IntSize(64, 32), scale).copyArgb(), rasterizeHeadless(actual, IntSize(64, 32), scale).copyArgb())
        }
        return visible
    }

    private fun intersects(
        command: DrawCommand,
        viewport: IntRect,
    ): Boolean {
        val bounds =
            when (command) {
                is DrawCommand.BlitImage -> command.destination.let { MinecraftTextInkBounds(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()) }
                is DrawCommand.SampledImage -> command.destination.let { MinecraftTextInkBounds(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()) }
                else -> error("The text layout emitted an unexpected command.")
            }
        val horizontal = bounds.left < viewport.right.toDouble() && viewport.left.toDouble() < bounds.right
        val vertical = bounds.top < viewport.bottom.toDouble() && viewport.top.toDouble() < bounds.bottom
        return horizontal && vertical
    }

    private fun capture(
        layout: MinecraftTextLayout,
        origin: IntOffset,
        viewport: IntRect? = null,
    ): Capture {
        val scope = MinecraftTextRecordingScope()
        val visits =
            if (viewport == null) {
                layout.paint(scope, origin.x, origin.y)
                0
            } else {
                layout.paintVisible(scope, viewport, origin.x, origin.y)
            }
        return Capture(scope.commands, visits)
    }

    private fun layout(
        renderer: MinecraftTextRenderer,
        value: String,
        spacing: Int = 0,
    ): MinecraftTextLayout =
        MinecraftTextLineBreaker.create(
            MinecraftTextContent.create(UiText.Literal(value), multiline = true),
            renderer,
            TextLayout.Multiline(TextWrap.None, lineSpacing = spacing),
            Int.MAX_VALUE,
            TextStyle.Normal,
            logicalOrder = true,
        )

    private fun sampledRenderer(
        glyphs: Map<Int, MinecraftFontGlyph>,
        interleaved: Boolean = true,
    ): MinecraftTextRenderer {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:visible.ttf"}"""),
                "assets/test/font/visible.ttf" to byteArrayOf(1),
                capabilities = FontTestResources.compatibility.copy(interleavedShadows = interleaved, preparedTextBounds = true),
            )
        val backend =
            FontTestBackend(open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph? = glyphs[codePoint]

                    override fun close() = Unit
                }
            })
        return MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { backend }))
    }

    private fun legacyRenderer(): MinecraftTextRenderer {
        val foreground = createDrawImage(IntSize(8, 8), IntArray(64) { if (it % 3 == 0) -1 else 0xFF50A0E0.toInt() })
        val shadow = createDrawImage(IntSize(8, 8), IntArray(64) { 0x80304050.toInt() })
        val glyph = MinecraftGlyphSnapshot.create(3, shadow, foreground, shadow, foreground, shadow, foreground, shadow, foreground, foreground)
        return MinecraftTextRenderer.legacy((0x21..0x7E).associateWith { glyph })
    }

    private fun sampledImage(): DrawImage = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x4080C0FF, 0xCC4080C0.toInt(), 0x00FFFFFF))

    private data class Capture(
        val commands: List<DrawCommand>,
        val visits: Int,
    )

    private data class SampledCase(
        val scroll: IntOffset,
        val rows: Int,
        val candidatesPerLine: Int,
        val commands: Int,
    )
}
