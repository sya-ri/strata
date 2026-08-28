package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies atomic normalized composition budgets and one bounded current snapshot without native input-method dependencies.
 */
internal class MinecraftTextAreaCompositionTest {
    @Test
    fun exactBudgetAcceptsSupplementaryTextAndOneExtraUnitIsRejected() {
        val exact = TextInputEvent.Preedit("🙂A", 2, listOf("🙂", "A"), 1)
        assertEquals(MinecraftTextAreaPreedit("🙂A", 2, 2..2), MinecraftTextAreaComposition.normalize(exact, 3))
        assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit("🙂AA", 2, emptyList(), -1), 3))
        assertNull(MinecraftTextAreaComposition.normalize(exact, 2))
        assertEquals(
            MinecraftTextAreaPreedit("", 0, null),
            MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit("", 0, emptyList(), -1), 0),
        )
        assertThrows(IllegalArgumentException::class.java) { MinecraftTextAreaComposition.normalize(exact, -1) }
    }

    @Test
    fun crlfContractionUsesNormalizedCapacityAndMapsEveryIntermediateCaret() {
        val value = "\r\n\r\n\r\n"
        val blocks = listOf("\r", "\n", "\r", "\n", "\r", "\n")
        val offsets = listOf(0, 1, 1, 2, 2, 3, 3)
        offsets.forEachIndexed { raw, normalized ->
            assertEquals(
                MinecraftTextAreaPreedit("\n\n\n", normalized, IntRange.EMPTY),
                MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit(value, raw, blocks, 3), 3),
            )
        }
        assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit(value, 6, blocks, 3), 2))
    }

    @Test
    fun metadataIsBudgetedWithoutRetainingRawBlocksAndOnlyMatchingBlocksDecorate() {
        val mismatch = TextInputEvent.Preedit("日🙂", 3, listOf("한", "A"), 1)
        assertEquals(MinecraftTextAreaPreedit("日🙂", 3, null), MinecraftTextAreaComposition.normalize(mismatch, 3))
        assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit("A", 1, listOf("A".repeat(7)), 0), 3))
        assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit("A", 1, List(8) { "" }, 0), 3))
        assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit("🙂", 1, emptyList(), -1), 3))
        assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit("🙂", 2, listOf("\uD83D", "\uDE42"), 0), 3))
        assertNotNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit("🙂", 2, listOf("", "🙂", ""), 1), 2))
    }

    @Test
    fun replacementUsesRemainingCommittedCapacityWithoutAccumulatingPreviousPreedit() {
        MinecraftTextAreaFixture(cacheEntries = 0).use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("日", maxLength = 4)
                tree.update(fixture.description(state))
                fixture.frame(tree)
                repeat(8) { index ->
                    val value = if (index % 2 == 0) "🙂A" else "한🙂"
                    val frame = fixture.input(tree, TextInputEvent.Preedit(value, value.length, listOf(value), 0))
                    assertEquals(6, frame.filterIsInstance<DrawCommand.SampledImage>().size)
                    assertEquals("日", state.value)
                }
                val last = fixture.frame(tree)
                val calls = fixture.glyphCalls
                fixture.input(tree, TextInputEvent.Preedit("🙂AA", 4, emptyList(), -1))
                assertEquals(calls, fixture.glyphCalls)
                assertEquals(last, fixture.frame(tree))
                state.value = "한"
                val committed = fixture.frame(tree)
                assertEquals(2, committed.filterIsInstance<DrawCommand.SampledImage>().size)
                assertEquals("한", state.value)
            }
        }
    }

    @Test
    fun underlineIncludesIntermediateScalarExtremaForCancellingNegativeAndZeroAdvances() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val advances = mapOf('A'.code to 4f, 'B'.code to -4f, 'Z'.code to 0f)
        val cases =
            listOf(
                Triple("", "AB", listOf(IntRect(4, 12, 8, 13))),
                Triple("A", "B", listOf(IntRect(4, 12, 8, 13))),
                Triple("", "Z", emptyList()),
                Triple("", "AB\nAB", listOf(IntRect(4, 12, 8, 13), IntRect(4, 21, 8, 22))),
            )
        MinecraftTextAreaFixture(glyph = { _, codePoint ->
            MinecraftFontGlyph(advances.getValue(codePoint), 0f, 0f, 1f, 1f, image)
        }).use { fixture ->
            for ((committed, composition, expected) in cases) {
                UiTree().use { tree ->
                    val state = TextAreaState(committed)
                    tree.update(fixture.description(state))
                    fixture.frame(tree)
                    val commands = fixture.input(tree, TextInputEvent.Preedit(composition, composition.length, listOf(composition), 0))
                    val underlines = commands.filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds }.filter { it.height == 1 }
                    assertEquals(expected, underlines)
                    assertEquals(committed, state.value)
                }
            }
        }
    }
}
