package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.input.TextInputEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies immutable canonical reuse only after complete validation, and lazy conversion after long prefixes.
 */
internal class MinecraftCanonicalPreeditTest {
    @Test
    fun canonicalIdentityIncludesLfAndSupplementaryTextButRetainsNoCallerBlocks() {
        listOf("ASCII", "日本語", "🙂𐐀", "A\n🙂\n", "", "A".repeat(16_384)).forEach { text ->
            val blocks = if (text.isEmpty()) mutableListOf(text) else mutableListOf("", text, "")
            val event = TextInputEvent.Preedit(text, text.length, blocks, blocks.size / 2)
            blocks.clear()
            val normalized = checkNotNull(MinecraftTextAreaComposition.normalize(event, text.length))
            assertSame(text, normalized.fullText)
            assertEquals(text.length, normalized.caretPosition)
            assertEquals(0 until text.length, normalized.focusedRange)
        }
    }

    @Test
    fun everyMandatoryConversionPreservesCanonicalPrefixesAndMappedBoundaries() {
        val prefix = "A".repeat(4_096) + "🙂\n"
        listOf("\r", "\r\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029").forEach { separator ->
            val raw = prefix + separator + "日🙂"
            val expected = prefix + "\n日🙂"
            val blocks = listOf(prefix, separator.take(1), separator.drop(1), "日🙂")
            for (caret in prefix.length..prefix.length + separator.length) {
                val event = TextInputEvent.Preedit(raw, caret, blocks, 1)
                val normalized = checkNotNull(MinecraftTextAreaComposition.normalize(event, expected.length))
                assertEquals(expected, normalized.fullText)
                assertNotSame(raw, normalized.fullText)
                assertEquals(prefix.length + if (caret == prefix.length) 0 else 1, normalized.caretPosition)
                assertEquals(prefix.length until prefix.length + 1, normalized.focusedRange)
            }
            assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit(raw, raw.length, blocks, 3), expected.length - 1))
        }
    }

    @Test
    fun matchingCanonicalInputStillRejectsLateControlsSurrogatesAndCapacityOverflow() {
        val prefix = "A".repeat(16_384)
        listOf("\u0000", "\u0001", "\u007F", "§", "\uD83D", "\uDE42").forEach { suffix ->
            val raw = prefix + suffix
            assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit(raw, raw.length, listOf(raw), 0), raw.length))
            assertNull(MinecraftTextAreaComposition.normalize(TextInputEvent.Preedit(prefix, prefix.length, listOf("mismatch", suffix), 0), raw.length))
        }
        val event = TextInputEvent.Preedit(prefix, prefix.length, listOf(prefix), 0)
        assertNull(MinecraftTextAreaComposition.normalize(event, prefix.length - 1))
        assertSame(prefix, checkNotNull(MinecraftTextAreaComposition.normalize(event, prefix.length)).fullText)
    }
}
