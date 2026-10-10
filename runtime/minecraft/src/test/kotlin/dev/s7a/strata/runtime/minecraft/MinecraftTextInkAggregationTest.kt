package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Frozen literal geometry controls for both ink aggregations, independent of their implementation.
 * Expected extrema and ordered commands are supplied explicitly, including extreme coordinates.
 * Existing retained-session/editor/lifetime tests supply the remaining whole-operation controls.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextInkAggregationTest {
    private val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x4080C0FF, 0xCC4080C0.toInt(), 0x00FFFFFF))
    private val foreground = ArgbColor(0xCC80A0D0.toInt())
    private val shadow = ArgbColor(0x80406080.toInt())
    private val source = FloatRect(0.01f, 0.01f, 1.99f, 1.99f)

    @Test
    fun emptyRunAndLayoutRemainAbsent() {
        val empty = sampled("", emptyMap())
        assertNull(empty.inkBounds())
        assertNull(layout(emptyList()).inkBounds())
        assertNull(layout(listOf(empty, empty)).inkBounds())
        assertTrue(commands(empty).isEmpty())
    }

    @Test
    fun spacingOnlyKeepsLogicalWidthWithoutInk() {
        val run = sampled("AA", mapOf('A'.code to MinecraftFontGlyph(3f, 0f, 0f, 0f, 0f, null)))
        assertEquals(6, run.nativeWidth)
        assertNull(run.inkBounds())
        assertNull(layout(listOf(run, run)).inkBounds())
        assertTrue(commands(run).isEmpty())
    }

    @Test
    fun singleSampledQuadAndShadowUseLiteralFractionalExtrema() {
        val glyph = MinecraftFontGlyph(3f, -0.375f, -0.25f, 1.375f, 1.75f, image, shadowOffset = 1.5f)
        val single = sampled("A", mapOf('A'.code to glyph))
        bounds(single.inkBounds(), -0.375, -0.25, 1.375, 1.75)
        assertEquals(listOf(command(FloatRect(-0.375f, -0.25f, 1.375f, 1.75f))), commands(single))
        for (tint in listOf(ArgbColor(0), shadow)) {
            val shadowed = sampled("A", mapOf('A'.code to glyph), tint)
            bounds(shadowed.inkBounds(), -0.375, -0.25, 2.875, 3.25)
            assertEquals(listOf(command(FloatRect(1.125f, 1.25f, 2.875f, 3.25f), tint), command(FloatRect(-0.375f, -0.25f, 1.375f, 1.75f))), commands(shadowed))
        }
    }

    @Test
    fun legacyForegroundAndShadowKeepExactIntegerDoubleEdges() {
        bounds(legacy("AB", shadows = false).inkBounds(), 0.0, 0.0, 11.0, 8.0)
        bounds(legacy("AB").inkBounds(), 0.0, 0.0, 12.0, 9.0)
        bounds(legacy("A", shadows = false).inkBounds(), 0.0, 0.0, 8.0, 8.0)
        bounds(layout(listOf(legacy("A"), legacy("AB")), 11).inkBounds(), 0.0, 0.0, 12.0, 20.0)
    }

    @Test
    fun orientationsKeepNormalizedGeometryAndPreparedRejection() {
        val base = MinecraftFontGlyph(2f, 0f, 0f, 1f, 1f, image, shadowOffset = 2f)
        for (orientation in SampledImageOrientation.entries) {
            val glyph = base.copy(orientation = orientation)
            val unprepared = sampled("A", mapOf('A'.code to glyph), prepared = false)
            bounds(unprepared.inkBounds(), 0.0, 0.0, 1.0, 1.0)
            assertEquals(listOf(command(FloatRect(0f, 0f, 1f, 1f), orientation = orientation)), commands(unprepared))
            val prepared = sampled("A", mapOf('A'.code to glyph))
            if (orientation == SampledImageOrientation.Normal) bounds(prepared.inkBounds(), 0.0, 0.0, 1.0, 1.0) else assertNull(prepared.inkBounds())
            val expanded = sampled("A", mapOf('A'.code to glyph), shadow)
            bounds(expanded.inkBounds(), 0.0, 0.0, 3.0, 3.0)
            assertEquals(listOf(command(FloatRect(2f, 2f, 3f, 3f), shadow, orientation), command(FloatRect(0f, 0f, 1f, 1f), orientation = orientation)), commands(expanded))
        }
    }

    @Test
    fun signedCancellingAndZeroAdvancesKeepOverlaps() {
        val glyph = MinecraftFontGlyph(6f, -1f, -2f, 2f, 3f, image)
        val run = sampled("ABC", mapOf('A'.code to glyph, 'B'.code to glyph.copy(advance = -3f), 'C'.code to glyph.copy(advance = 0f)))
        bounds(run.inkBounds(), -1.0, -2.0, 8.0, 3.0)
        assertEquals(listOf(FloatRect(-1f, -2f, 2f, 3f), FloatRect(5f, -2f, 8f, 3f), FloatRect(2f, -2f, 5f, 3f)), commands(run).filterIsInstance<DrawCommand.SampledImage>().map { it.destination })
        for (advance in listOf(0f, -0f)) {
            val equal = sampled("AAA", mapOf('A'.code to glyph.copy(advance = advance)))
            bounds(equal.inkBounds(), -1.0, -2.0, 2.0, 3.0)
            assertEquals(List(3) { command(FloatRect(-1f, -2f, 2f, 3f)) }, commands(equal))
        }
    }

    @Test
    fun distantFiniteQuadsKeepADoubleUnionBeyondFloatExtent() {
        val left = MinecraftFontGlyph(0f, -2e38f, 0f, -1e38f, 1f, image)
        val right = MinecraftFontGlyph(0f, 1e38f, 0f, 2e38f, 1f, image)
        val run = sampled("AB", mapOf('A'.code to left, 'B'.code to right))
        val expected = bounds(run.inkBounds(), (-2e38f).toDouble(), 0.0, 2e38f.toDouble(), 1.0)
        assertTrue(Float.MAX_VALUE.toDouble() < expected.right - expected.left)
        assertEquals(listOf(command(FloatRect(-2e38f, 0f, -1e38f, 1f)), command(FloatRect(1e38f, 0f, 2e38f, 1f))), commands(run))
        bounds(layout(listOf(run, run), 9).inkBounds(), (-2e38f).toDouble(), 0.0, 2e38f.toDouble(), 10.0)
    }

    @Test
    fun legacyCoordinatesAboveFloatPrecisionAndNearIntegerLimitStayExact() {
        for (advance in listOf(16_777_217, Int.MAX_VALUE - 9)) {
            val run = legacy("AB", advance = { if (it == 'A'.code) advance else 1 })
            bounds(run.inkBounds(), 0.0, 0.0, advance.toDouble() + 9.0, 9.0)
            val expected = listOf(IntRect(1, 1, 9, 9), IntRect(0, 0, 8, 8), IntRect(advance + 1, 1, advance + 9, 9), IntRect(advance, 0, advance + 8, 8))
            assertEquals(expected, commands(run).filterIsInstance<DrawCommand.BlitImage>().map { it.destination })
        }
    }

    @Test
    fun originZeroCollapseDoesNotChangeActualOriginPainting() {
        val spacing = MinecraftFontGlyph(16_777_216f, 0f, 0f, 0f, 0f, null)
        val narrow = MinecraftFontGlyph(2f, 0.25f, 0f, 0.75f, 1f, image, shadowOffset = 0.5f)
        val run = sampled("PA", mapOf('P'.code to spacing, 'A'.code to narrow), shadow)
        assertNull(run.inkBounds())
        assertNull(layout(listOf(run)).inkBounds())
        assertEquals(listOf(command(FloatRect(0.75f, 4.5f, 1.25f, 5.5f), shadow), command(FloatRect(0.25f, 4f, 0.75f, 5f))), commands(run, -16_777_216, 4))
        assertEquals(MinecraftTextVerticalBounds(4.0, 5.5), run.verticalInkAt(4))
    }

    @Test
    fun invalidAndNonfiniteMetricsPreserveTheDrawablePrefix() {
        val visible = MinecraftFontGlyph(2f, 0f, 0f, 1f, 1f, image)
        for (advance in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val run = sampled("ABC", mapOf('A'.code to visible, 'B'.code to visible.copy(advance = advance), 'C'.code to visible))
            bounds(run.inkBounds(), 0.0, 0.0, 3.0, 1.0)
            assertEquals(listOf(command(FloatRect(0f, 0f, 1f, 1f)), command(FloatRect(2f, 0f, 3f, 1f))), commands(run))
        }
        val invalid = listOf(visible.copy(left = Float.NaN), visible.copy(right = Float.POSITIVE_INFINITY), visible.copy(left = -Float.MAX_VALUE, right = Float.MAX_VALUE), visible.copy(left = 0f, right = 0f), visible.copy(top = 0f, bottom = 0f))
        for (glyph in invalid) {
            for (prepared in listOf(false, true)) {
                val run = sampled("AXB", mapOf('A'.code to visible, 'X'.code to glyph, 'B'.code to visible), prepared = prepared)
                bounds(run.inkBounds(), 0.0, 0.0, 5.0, 1.0)
                assertEquals(listOf(command(FloatRect(0f, 0f, 1f, 1f)), command(FloatRect(4f, 0f, 5f, 1f))), commands(run))
            }
        }
        val nan = sampled("A", mapOf('A'.code to visible.copy(left = Float.NaN)))
        assertNull(nan.inkBounds())
        assertTrue(commands(nan).isEmpty())
        val nanPrefix = sampled("AB", mapOf('A'.code to visible.copy(left = Float.NaN), 'B'.code to visible))
        bounds(nanPrefix.inkBounds(), 2.0, 0.0, 3.0, 1.0)
        assertEquals(listOf(command(FloatRect(2f, 0f, 3f, 1f))), commands(nanPrefix))
    }

    @Test
    fun multipleLinesSkipEmptyInkAndTranslateExactDoubleEdges() {
        val empty = sampled("", emptyMap())
        val glyph = MinecraftFontGlyph(2f, -2f, -3f, 7f, 14f, image)
        val run = sampled("A", mapOf('A'.code to glyph), shadow)
        bounds(layout(listOf(empty, run, empty, run), 33_554_433).inkBounds(), -2.0, 33_554_430.0, 8.0, 100_663_314.0)
        bounds(layout(listOf(run, empty, run), 11).inkBounds(), -2.0, -3.0, 8.0, 37.0)
    }

    @Test
    fun checkedTranslationAndClipOverflowStillFailAtTheOriginalBoundary() {
        val run = legacy("A")
        val empty = sampled("", emptyMap())
        assertThrows(ArithmeticException::class.java) { layout(listOf(run, empty, run), Int.MAX_VALUE).inkBounds() }
        bounds(layout(listOf(run, empty, empty), Int.MAX_VALUE).inkBounds(), 0.0, 0.0, 9.0, 9.0)
        val glyph = MinecraftFontGlyph(1f, -1f, -3e9f, 2f, -2e9f, image)
        val extreme = layout(listOf(sampled("A", mapOf('A'.code to glyph))))
        assertThrows(ArithmeticException::class.java) { MinecraftMultilineTextViewport.clipBounds(extreme, Constraints(maxWidth = 1)) }
    }

    @Test
    fun viewportAxesPreserveUnconstrainedNegativeOverhang() {
        val glyph = MinecraftFontGlyph(2f, -2f, -3f, 7f, 14f, image)
        val result = layout(listOf(sampled("A", mapOf('A'.code to glyph))))
        assertNull(MinecraftMultilineTextViewport.clipBounds(result, Constraints()))
        assertNull(MinecraftMultilineTextViewport.clipBounds(result, Constraints(maxWidth = 100, maxHeight = 100)))
        assertEquals(IntRect(0, -3, 4, 14), MinecraftMultilineTextViewport.clipBounds(result, Constraints(maxWidth = 4)))
        assertEquals(IntRect(-2, 0, 7, 2), MinecraftMultilineTextViewport.clipBounds(result, Constraints(maxHeight = 2)))
        assertEquals(IntRect(0, 0, 4, 9), MinecraftMultilineTextViewport.clipBounds(result, Constraints.fixed(4, 9)))
    }

    @Test
    fun signedZeroMinMaxMatchesTheOriginalImmutableContract() {
        val first = MinecraftTextInkBounds(0.0, -0.0, -0.0, 0.0)
        val second = MinecraftTextInkBounds(-0.0, 0.0, 0.0, -0.0)
        bounds(first.union(second), -0.0, -0.0, 0.0, 0.0)
        bounds(second.union(first), -0.0, -0.0, 0.0, 0.0)
    }

    @Test
    fun cleanEditorKeepsCurrentLayoutAndLineBoundsAndReplacementReleasesOnlyCurrentOwnership() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            val state = TextAreaState("AB\nA")
            var callbacks = 0
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { callbacks += 1 }
            val field = MinecraftTextAreaEditor::class.java.getDeclaredField("layout").also { it.isAccessible = true }
            try {
                editor.attach()
                editor.measure()
                val first = field.get(editor) as MinecraftTextLayout
                val ink = first.lines.map { it.inkBounds }
                val calls = fixture.glyphCalls
                val callbackCount = callbacks
                editor.measure()
                assertSame(first, field.get(editor))
                first.lines.forEachIndexed { index, line -> assertSame(ink[index], line.inkBounds) }
                assertEquals(calls, fixture.glyphCalls)
                assertEquals(callbackCount, callbacks)
                state.value = "A"
                editor.measure()
                assertNotSame(first, field.get(editor))
                assertTrue(calls < fixture.glyphCalls)
                val detached = commands(first.lines.first().run)
                editor.dispose()
                assertNull(field.get(editor))
                assertEquals(detached, commands(first.lines.first().run))
                first.lines.forEachIndexed { index, line -> assertSame(ink[index], line.inkBounds) }
            } finally {
                editor.dispose()
            }
            state.observe {}.close()
        }
    }

    @Test
    fun completeOrderedLiteralCommandsKeepFullAndClippedPixelsAtAllDensities() {
        val glyph = MinecraftFontGlyph(2f, -0.375f, -0.25f, 1.375f, 1.75f, image, shadowOffset = 1.5f)
        for (interleaved in listOf(false, true)) {
            val run = sampled("AA", mapOf('A'.code to glyph), shadow, interleaved = interleaved)
            bounds(run.inkBounds(), -0.375, -0.25, 4.875, 3.25)
            val firstShadow = command(FloatRect(3.125f, 3.25f, 4.875f, 5.25f), shadow)
            val secondShadow = command(FloatRect(5.125f, 3.25f, 6.875f, 5.25f), shadow)
            val firstForeground = command(FloatRect(1.625f, 1.75f, 3.375f, 3.75f))
            val secondForeground = command(FloatRect(3.625f, 1.75f, 5.375f, 3.75f))
            val expected = if (interleaved) listOf(firstShadow, firstForeground, secondShadow, secondForeground) else listOf(firstShadow, secondShadow, firstForeground, secondForeground)
            val actual = commands(run, 2, 2)
            assertEquals(expected, actual)
            val clip = IntRect(2, 2, 5, 5)
            val expectedClipped = listOf(DrawCommand.PushClip(clip)) + expected + DrawCommand.PopClip
            val actualClipped = listOf(DrawCommand.PushClip(clip)) + actual + DrawCommand.PopClip
            for (density in 1..3) {
                assertArrayEquals(rasterizeHeadless(expected, IntSize(8, 8), density).copyArgb(), rasterizeHeadless(actual, IntSize(8, 8), density).copyArgb())
                assertArrayEquals(rasterizeHeadless(expectedClipped, IntSize(8, 8), density).copyArgb(), rasterizeHeadless(actualClipped, IntSize(8, 8), density).copyArgb())
            }
        }
    }

    private fun bounds(
        actual: MinecraftTextInkBounds?,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): MinecraftTextInkBounds {
        val result = checkNotNull(actual)
        assertArrayEquals(longArrayOf(left.toRawBits(), top.toRawBits(), right.toRawBits(), bottom.toRawBits()), longArrayOf(result.left.toRawBits(), result.top.toRawBits(), result.right.toRawBits(), result.bottom.toRawBits()))
        return result
    }

    private fun command(
        destination: FloatRect,
        tint: ArgbColor = foreground,
        orientation: SampledImageOrientation = SampledImageOrientation.Normal,
    ): DrawCommand.SampledImage = DrawCommand.SampledImage(image, source, destination, tint, 0.1f, orientation)

    private fun commands(
        run: MinecraftTextRun,
        x: Int = 0,
        y: Int = 0,
    ): List<DrawCommand> = MinecraftTextRecordingScope().also { run.paint(it, x, y) }.commands

    private fun sampled(
        text: String,
        glyphs: Map<Int, MinecraftFontGlyph>,
        tint: ArgbColor? = null,
        prepared: Boolean = true,
        interleaved: Boolean = true,
    ): MinecraftTextRun {
        val snapshot = FontTestResources.snapshot(FontTestResources.font("default", """{"type":"ttf","file":"test:ink.ttf"}"""), "assets/test/font/ink.ttf" to byteArrayOf(1), capabilities = FontTestResources.compatibility.copy(preparedTextBounds = prepared, interleavedShadows = interleaved))
        val backend = FontTestBackend(open = { _, _ ->
            object : MinecraftTrueTypeFace {
                override fun glyph(codePoint: Int): MinecraftFontGlyph? = glyphs[codePoint]

                override fun close() = Unit
            }
        })
        return MinecraftFontEngine(snapshot, { backend }).use { engine -> MinecraftTextRun.createFonts(UiText.Literal(text), engine, FontTestResources.defaultFont, foreground, tint, logicalOrder = true) }
    }

    private fun legacy(
        text: String,
        shadows: Boolean = true,
        advance: (Int) -> Int = { 3 },
    ): MinecraftTextRun {
        val pixels = createDrawImage(IntSize(8, 8), IntArray(64) { -1 })
        val lookup = { codePoint: Int -> MinecraftGlyphSnapshot.create(advance(codePoint), pixels, pixels, pixels, pixels, pixels, pixels, pixels, pixels, pixels) }
        return if (shadows) MinecraftTextRun.createNormal(UiText.Literal(text), lookup) else MinecraftTextRun.createContainerLabel(UiText.Literal(text), lookup)
    }

    private fun layout(
        runs: List<MinecraftTextRun>,
        step: Int = 9,
    ): MinecraftTextLayout = MinecraftTextLayout(MinecraftTextContent.create(UiText.Literal("")), runs.map { MinecraftTextLine(0, 0, 0, it, intArrayOf(0), intArrayOf(0)) }, IntSize(runs.maxOfOrNull { it.size.width } ?: 0, 9), step, false)
}
