package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Preserves defensive binary construction, compact current-line metrics and immutable old layouts across owner changes.
 * Tests borrow the real deterministic font service and retained editor; no game or native library is required.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextLineOwnershipTest {
    @Test
    fun arbitraryArraysStayIsolatedThroughTheOriginalSixArgumentJvmConstructor() {
        MinecraftTextRenderer.legacy(emptyMap()).use { renderer ->
            val run = renderer.create(UiText.Literal(""), TextStyle.Normal)
            val constructor =
                MinecraftTextLine::class.java.getDeclaredConstructor(
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE,
                    MinecraftTextRun::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                )
            assertTrue(Modifier.isPublic(constructor.modifiers) && constructor.isSynthetic.not())
            val offsets = intArrayOf(0, 1, 3, 4)
            val positions = intArrayOf(0, 4, 4, -2)
            val line = constructor.newInstance(0, 4, 6, run, offsets, positions)
            offsets.fill(99)
            positions.fill(Int.MIN_VALUE)
            assertArrayEquals(intArrayOf(0, 1, 3, 4), array(line, "offsets"))
            assertArrayEquals(intArrayOf(0, 4, 4, -2), array(line, "positions"))
            assertEquals(1, line.offsetAt(4))
            assertEquals(-2..4, line.caretExtents(0, 4))
            assertSame(run, line.run)
            assertEquals(6, line.nextStart)
        }
    }

    @Test
    fun privilegedFreshLineMatchesDefensiveScalarAndSignedCoordinateLookups() {
        MinecraftTextRenderer.legacy(emptyMap()).use { renderer ->
            val run = renderer.create(UiText.Literal(""), TextStyle.Normal)
            val offsets = intArrayOf(0, 1, 3, 4, 5, 6)
            val positions = intArrayOf(0, 4, 4, -3, Int.MAX_VALUE, Int.MIN_VALUE)
            val expected = MinecraftTextLine(0, 6, 8, run, offsets, positions)
            val actual = MinecraftTextLine.createOwned(0, 6, 8, run, offsets.copyOf(), positions.copyOf())
            for (offset in 0..6) assertEquals(expected.caretX(offset), actual.caretX(offset))
            for (x in listOf(Int.MIN_VALUE, -4, 0, 2, 4, Int.MAX_VALUE)) assertEquals(expected.offsetAt(x), actual.offsetAt(x))
            for (start in 0..6) {
                for (end in start..6) assertEquals(expected.caretExtents(start, end), actual.caretExtents(start, end))
            }
            assertSame(run, actual.run)
            assertEquals(8, actual.nextStart)
        }
    }

    @Test
    fun producerKeepsOnlyTheVisibleEllipsisBoundariesForFirstMiddleAndLastCuts() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        MinecraftTextAreaFixture(glyph = { _, _ -> MinecraftFontGlyph(3f, 0f, 0f, 1f, 1f, image) }).use { fixture ->
            val renderer = fixture.configuration(TextAreaState()).renderer
            val value = "A".repeat(32_767)
            for (width in listOf(9, 12, 48, 98_298)) {
                val line =
                    MinecraftTextLineBreaker
                        .create(
                            MinecraftTextContent.create(UiText.Literal(value), multiline = true),
                            renderer,
                            TextLayout.Multiline(TextWrap.None, overflow = TextOverflow.Ellipsis),
                            width,
                            TextStyle.Normal,
                            logicalOrder = true,
                        ).lines
                        .single()
                val visible = (width - 9) / 3
                assertEquals(visible, line.end)
                assertEquals(value.length, line.nextStart)
                assertArrayEquals(IntArray(visible + 1) { it }, array(line, "offsets"))
                assertArrayEquals(IntArray(visible + 1) { it * 3 }, array(line, "positions"))
                assertEquals(value.take(visible) + "...", MinecraftTextContent.create(line.run.text).value)
            }
        }
    }

    @Test
    fun supplementaryAndCrLfLinesRetainOriginalOffsetsAndConsumedBreakStarts() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        MinecraftTextAreaFixture(glyph = { _, _ -> MinecraftFontGlyph(3f, 0f, 0f, 1f, 1f, image) }).use { fixture ->
            val renderer = fixture.configuration(TextAreaState()).renderer
            val layout =
                MinecraftTextLineBreaker.create(
                    MinecraftTextContent.create(UiText.Literal("A🙂\r\nBZ\n"), multiline = true),
                    renderer,
                    TextLayout.Multiline(TextWrap.None),
                    Int.MAX_VALUE,
                    TextStyle.Normal,
                    logicalOrder = true,
                )
            val expected = listOf(intArrayOf(0, 1, 3), intArrayOf(5, 6, 7), intArrayOf(8))
            assertEquals(3, layout.lines.size)
            assertEquals(listOf(5, 8, 8), layout.lines.map { it.nextStart })
            for ((line, offsets) in layout.lines.zip(expected)) {
                assertArrayEquals(offsets, array(line, "offsets"))
                assertArrayEquals(IntArray(offsets.size) { it * 3 }, array(line, "positions"))
                assertEquals(0..((offsets.size - 1) * 3), line.caretExtents(line.start, line.end))
            }
        }
    }

    @Test
    fun oldLinesRemainImmutableAfterEditReflowCompositionLossDetachAndDisposal() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            val state = TextAreaState("AB🙂CD")
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { _ -> }
            editor.attach()
            editor.focus(true)
            editor.measure()
            val field = MinecraftTextAreaEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
            val original = checkNotNull(field.get(editor)) as MinecraftTextLayout
            val snapshots = original.lines.map { line -> array(line, "offsets").copyOf() to array(line, "positions").copyOf() }
            try {
                state.value = "🙂BZ\nCD"
                editor.measure()
                editor.update(fixture.configuration(state, IntSize(20, 26)))
                editor.measure()
                editor.textInput(TextInputEvent.Preedit("🙂Z", 2, listOf("🙂", "Z"), 0))
                editor.measure()
                state.value = "A"
                editor.measure()
                editor.detach()
                assertNull(field.get(editor))
                editor.dispose()
                assertNull(field.get(editor))
                assertNull(MinecraftTextAreaEditor::class.java.getDeclaredField("current").apply { isAccessible = true }.get(editor))
                for ((line, snapshot) in original.lines.zip(snapshots)) {
                    assertArrayEquals(snapshot.first, array(line, "offsets"))
                    assertArrayEquals(snapshot.second, array(line, "positions"))
                }
            } finally {
                editor.dispose()
            }
            state.observe {}.close()
        }
    }

    @Test
    fun failedLayoutDoesNotPublishPartialMetricsOrRetainTheStateAfterDisposal() {
        val failure = IllegalArgumentException("Rejected line glyph")
        var reject = false
        MinecraftTextAreaFixture(glyph = { _, _ -> if (reject) throw failure else MinecraftFontGlyph(3f, 0f, 0f, 0f, 0f, null) }, cacheEntries = 0).use { fixture ->
            val state = TextAreaState("AB")
            val editor = MinecraftTextAreaEditor(fixture.configuration(state)) { _ -> }
            editor.attach()
            editor.measure()
            val field = MinecraftTextAreaEditor::class.java.getDeclaredField("layout").apply { isAccessible = true }
            val original = checkNotNull(field.get(editor)) as MinecraftTextLayout
            val snapshot = array(original.lines.single(), "positions").copyOf()
            state.value = "ZZ"
            reject = true
            try {
                assertSame(failure, assertThrows(IllegalArgumentException::class.java) { editor.measure() })
                assertNull(field.get(editor))
                assertArrayEquals(snapshot, array(original.lines.single(), "positions"))
            } finally {
                editor.dispose()
            }
            state.observe {}.close()
        }
    }

    private fun array(
        line: MinecraftTextLine,
        name: String,
    ): IntArray =
        MinecraftTextLine::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(line) as IntArray
}
