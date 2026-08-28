package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies real portable clipping, current-viewport work bounds, conservative font overhang, and scroll repaint ownership.
 */
internal class MinecraftTextAreaViewportTest {
    @Test
    fun actualPaddedClipPreservesFractionalGlyphSamplingAtEveryPhysicalScale() {
        val source = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        val size = IntSize(19, 17)
        val clip = IntRect(4, 4, 15, 13)
        MinecraftTextAreaFixture(glyph = { _, _ -> MinecraftFontGlyph(3.5f, -0.75f, -2.25f, 2.75f, 10.25f, source) }).use { fixture ->
            UiTree().use { tree ->
                tree.update(fixture.description(TextAreaState("A".repeat(12)), size, focused = false))
                val commands = fixture.frame(tree, size)
                assertEquals(listOf(DrawCommand.PushClip(clip)), commands.filterIsInstance<DrawCommand.PushClip>())
                assertEquals(1, commands.count { it is DrawCommand.PopClip })
                val glyphs = commands.filterIsInstance<DrawCommand.SampledImage>()
                assertTrue(glyphs.any { it.destination.left < 4f })
                assertTrue(glyphs.any { clip.right.toFloat() < it.destination.right })
                assertEquals(FloatRect(0.01f, 0.01f, 1.99f, 1.99f), glyphs.first().source)
                for (scale in 1..3) assertClippedPixels(commands, size, clip, scale)
            }
        }
    }

    @Test
    fun maximumDefaultDocumentPaintWorkTracksTheViewportInsteadOfAllRows() {
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("A\n".repeat(16383) + "A")
                assertEquals(32767, state.value.length)
                tree.update(fixture.description(state, focused = false))
                val first = fixture.frame(tree)
                assertEquals(4, first.filterIsInstance<DrawCommand.SampledImage>().size)
                val calls = fixture.glyphCalls
                state.scrollState.scrollTo(70000.5)
                val middle = fixture.frame(tree)
                assertTrue(middle.filterIsInstance<DrawCommand.SampledImage>().size <= 6)
                tree.dispatchPointer(PointerEvent.Scroll(IntOffset(4, 4), 0.0, 1.0))
                val scrolled = fixture.frame(tree)
                assertTrue(scrolled.filterIsInstance<DrawCommand.SampledImage>().size <= 6)
                state.scrollState.scrollTo(state.scrollState.metrics.maximumOffset)
                assertEquals(4, fixture.frame(tree).filterIsInstance<DrawCommand.SampledImage>().size)
                assertEquals(calls, fixture.glyphCalls)
            }
        }
    }

    @Test
    fun maximumUnwrappedLineUsesHorizontalCullingBeforeAndAfterCaretPan() {
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("A".repeat(32767))
                tree.update(fixture.description(state))
                val first = fixture.frame(tree)
                assertTrue(first.filterIsInstance<DrawCommand.SampledImage>().size <= 18)
                fixture.key(tree, KeyCode.Home)
                val panned = fixture.key(tree, KeyCode.End)
                assertTrue(panned.filterIsInstance<DrawCommand.SampledImage>().size <= 18)
                assertEquals(1, panned.filterIsInstance<DrawCommand.FillRectangle>().size)
            }
        }
    }

    @Test
    fun largeHorizontalPanKeepsShadowInkExposedByNativeFloatCancellation() {
        val image = createDrawImage(IntSize(2, 8), IntArray(16) { -1 })
        val size = IntSize(32, 26)
        MinecraftTextAreaFixture(glyph = { _, _ -> MinecraftFontGlyph(33_554_456f, 33_554_428f, 0f, 33_554_432f, 8f, image) }).use { fixture ->
            UiTree().use { tree ->
                tree.update(fixture.description(TextAreaState("A"), size))
                fixture.frame(tree, size)
                fixture.key(tree, KeyCode.Home, size)
                val commands = fixture.key(tree, KeyCode.End, size)
                val shadow = commands.filterIsInstance<DrawCommand.SampledImage>().single()
                assertEquals(FloatRect(1f, 5f, 5f, 13f), shadow.destination)
                assertEquals(0xFF383838.toInt(), shadow.tint.value)
                val raster = rasterizeHeadless(commands, size)
                assertEquals(0xFF383838.toInt(), raster.argbAt(4, 5))
                assertEquals(0xFF606060.toInt(), raster.argbAt(5, 5))
            }
        }
    }

    @Test
    fun pannedLineCanPaintInkThatCollapsesAtTheUntranslatedOrigin() {
        val image = createDrawImage(IntSize(2, 8), IntArray(16) { -1 })
        val glyphs =
            mapOf(
                'P'.code to MinecraftFontGlyph(16_777_216f, 0f, 0f, 0f, 0f, null),
                'A'.code to MinecraftFontGlyph(0.25f, 0.25f, 9f, 0.75f, 17f, image),
            )
        val size = IntSize(32, 26)
        MinecraftTextAreaFixture(glyph = { _, codePoint -> glyphs[codePoint] }).use { fixture ->
            UiTree().use { tree ->
                tree.update(fixture.description(TextAreaState("PAAA"), size))
                assertTrue(fixture.frame(tree, size).none { it is DrawCommand.SampledImage })
                fixture.key(tree, KeyCode.Home, size)
                val commands = fixture.key(tree, KeyCode.End, size)
                assertEquals(
                    List(3) { FloatRect(27.25f, 13f, 27.75f, 21f) },
                    commands.filterIsInstance<DrawCommand.SampledImage>().map { it.destination },
                )
                val raster = rasterizeHeadless(commands, size)
                assertEquals(0xFFE0E0E0.toInt(), raster.argbAt(27, 13))
                assertEquals(0xFF606060.toInt(), raster.argbAt(26, 13))
            }
        }
    }

    @Test
    fun largeLineSpacingKeepsTheSecondRowsShadowAfterActualOriginFloatCancellation() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val size = IntSize(20, 17)
        val glyph = MinecraftFontGlyph(2f, 0f, -33_554_436f, 1f, -33_554_432f, image)
        MinecraftTextAreaFixture(glyph = { _, _ -> glyph }).use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("A\nA")
                tree.update(fixture.description(state, size, focused = false, lineSpacing = 33_554_422))
                val commands = fixture.frame(tree, size)
                assertEquals(0.0, state.scrollState.metrics.offset)
                assertEquals(33_554_440, state.scrollState.metrics.contentExtent)
                val shadow = commands.filterIsInstance<DrawCommand.SampledImage>().single()
                assertEquals(FloatRect(5f, 1f, 6f, 5f), shadow.destination)
                assertEquals(0xFF383838.toInt(), shadow.tint.value)
                for (scale in 1..3) {
                    val raster = rasterizeHeadless(commands, size, scale)
                    assertEquals(0xFF383838.toInt(), raster.argbAt(5 * scale, 4 * scale))
                    assertEquals(0xFF505050.toInt(), raster.argbAt(4 * scale, 4 * scale))
                    assertEquals(0xFF505050.toInt(), raster.argbAt(5 * scale, 5 * scale))
                }
            }
        }
    }

    @Test
    fun signedVerticalBearingKeepsOffBoxRowsWhoseActualInkEntersTheViewport() {
        val image = createDrawImage(IntSize(2, 8), IntArray(16) { -1 })
        val size = IntSize(20, 17)
        MinecraftTextAreaFixture(glyph = { _, _ -> MinecraftFontGlyph(3f, -2f, -18f, 2f, -9f, image) }).use { fixture ->
            UiTree().use { tree ->
                tree.update(fixture.description(TextAreaState("A\nA\nA\nA"), size, focused = false))
                val commands = fixture.frame(tree, size)
                val glyphs = commands.filterIsInstance<DrawCommand.SampledImage>()
                assertTrue(2 <= glyphs.size && glyphs.size <= 4)
                assertTrue(glyphs.any { it.destination.top == 4f })
                assertEquals(0xFFE0E0E0.toInt(), rasterizeHeadless(commands, size).argbAt(4, 4))
            }
        }
    }

    @Test
    fun externalScrollBackAfterCaretFollowOrGeometryClampRepaintsTheNewRows() {
        val compact = IntSize(20, 17)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("A\nAA\nAAA\nAAAA")
                tree.update(fixture.description(state, compact))
                fixture.frame(tree, compact)
                fixture.key(tree, KeyCode.Home, compact, KeyboardModifiers(control = true))
                fixture.key(tree, KeyCode.End, compact, KeyboardModifiers(control = true))
                assertEquals(27.0, state.scrollState.metrics.offset)
                state.scrollState.scrollTo(0.0)
                val top = fixture.frame(tree, compact)
                assertEquals(2, top.filterIsInstance<DrawCommand.SampledImage>().size)
                assertTrue(top.none { it is DrawCommand.FillRectangle })
                state.scrollState.scrollTo(27.0)
                fixture.frame(tree, compact)
                val expanded = IntSize(20, 44)
                tree.update(fixture.description(state, expanded))
                fixture.frame(tree, expanded)
                assertEquals(0.0, state.scrollState.metrics.offset)
                tree.update(fixture.description(state, compact))
                fixture.frame(tree, compact)
                state.scrollState.scrollTo(27.0)
                assertEquals(8, fixture.frame(tree, compact).filterIsInstance<DrawCommand.SampledImage>().size)
            }
        }
    }

    @Test
    fun requestedVisibleLineCountUsesFixedNinePixelBoxesAndAdditionalSpacing() {
        val size = IntSize(32, 28)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("A\nB\nC")
                tree.update(fixture.description(state, size, lineSpacing = 2, viewport = TextAreaViewport.Lines(32, 2)))
                val commands = fixture.frame(tree, size)
                assertEquals(listOf(DrawCommand.PushClip(IntRect(4, 4, 28, 24))), commands.filterIsInstance<DrawCommand.PushClip>())
                assertEquals(20, state.scrollState.metrics.viewportExtent)
                assertEquals(31, state.scrollState.metrics.contentExtent)
            }
        }
    }

    private fun assertClippedPixels(
        commands: List<DrawCommand>,
        size: IntSize,
        clip: IntRect,
        scale: Int,
    ) {
        val unclipped = commands.filterNot { it is DrawCommand.PushClip || it is DrawCommand.PopClip }
        val actual = rasterizeHeadless(commands, size, scale)
        val reference = rasterizeHeadless(unclipped, size, scale)
        val physicalClip = IntRect(clip.left * scale, clip.top * scale, clip.right * scale, clip.bottom * scale)
        var changedInside = false
        for (y in 0 until actual.size.height) {
            for (x in 0 until actual.size.width) {
                if (IntOffset(x, y) in physicalClip) {
                    assertEquals(reference.argbAt(x, y), actual.argbAt(x, y))
                    changedInside = changedInside || actual.argbAt(x, y) != 0xFF505050.toInt()
                } else {
                    assertEquals(0xFF505050.toInt(), actual.argbAt(x, y))
                }
            }
        }
        assertTrue(changedInside)
    }
}
