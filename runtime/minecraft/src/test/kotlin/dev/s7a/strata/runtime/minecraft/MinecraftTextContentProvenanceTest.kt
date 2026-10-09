package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.text.PlatformText
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.Random

/**
 * Compares detached run provenance with the unchanged dense builder and scalar-slice implementation from 7059cf8.
 * Expected fonts, validation and slice structure never use the candidate's run construction or lookup.
 */
internal class MinecraftTextContentProvenanceTest {
    private val firstFont = ResourceId("test", "first")
    private val secondFont = ResourceId("test", "second")

    @Test
    fun everySmallScalarOffsetAndSliceMatchesOriginalDenseComposition() {
        val fragments = listOf("", "A", "🙂", "日", "\r\n", "\u000B\u000C\u0085\u2028\u2029")
        for (first in fragments) {
            for (second in fragments) {
                val literal = UiText.Literal(first)
                val inner = UiText.Literal(second).withFont(secondFont)
                val forms =
                    listOf(
                        UiText.concat(literal, inner),
                        UiText.concat(literal, inner, UiText.Literal("")).withFont(firstFont),
                        UiText.concat(UiText.Literal("").withFont(secondFont), literal, inner, UiText.Literal("").withFont(firstFont)),
                        UiText.concat(literal.withFont(firstFont), inner.withFont(firstFont)),
                    )
                for (text in forms) compareEverySlice(text)
            }
        }
    }

    @Test
    fun emptyAndEndpointSelectionsAndMinimalSlicesHaveExplicitExpectedFonts() {
        val text =
            UiText.concat(
                UiText.Literal("").withFont(secondFont),
                UiText.Literal("A"),
                UiText.Literal("").withFont(secondFont),
                UiText.Literal("🙂B").withFont(firstFont),
                UiText.Literal("").withFont(secondFont),
            )
        val content = MinecraftTextContent.create(text)
        assertEquals(MinecraftTextRenderer.defaultFont, content.fontAt(0))
        assertEquals(firstFont, content.fontAt(1))
        assertEquals(firstFont, content.fontAt(3))
        assertEquals(UiText.Literal("A"), content.slice(0, 1))
        assertEquals(UiText.Literal("🙂B").withFont(firstFont), content.slice(1, 4))
        assertEquals(UiText.concat(UiText.Literal("A"), UiText.Literal("🙂").withFont(firstFont)), content.slice(0, 3))
        assertEquals(UiText.Literal("").withFont(firstFont), content.slice(1, 1))
        assertEquals(UiText.Literal("").withFont(firstFont), content.slice(4, 4))
        val empty = UiText.concat(UiText.Literal("").withFont(secondFont).withFont(firstFont), UiText.Literal("").withFont(firstFont))
        val emptyContent = MinecraftTextContent.create(empty)
        assertEquals(secondFont, emptyContent.fontAt(0))
        assertEquals(UiText.Literal("").withFont(secondFont), emptyContent.slice(0, 0))
    }

    @Test
    fun equalIdentifierValuesCoalesceAcrossNestedAndEmptyWrappers() {
        val equalFont = ResourceId("test", "first")
        assertNotSame(firstFont, equalFont)
        val text =
            UiText.concat(
                UiText.Literal("A").withFont(firstFont),
                UiText.Literal("").withFont(secondFont),
                UiText.concat(UiText.Literal("🙂").withFont(equalFont), UiText.Literal("B").withFont(firstFont)).withFont(secondFont),
            )
        val content = MinecraftTextContent.create(text)
        assertEquals(UiText.Literal("A🙂B").withFont(firstFont), content.slice(0, 4))
        assertEquals(1, fonts(content).size)
        assertArrayEquals(IntArray(0), starts(content))
        compareEverySlice(text)
    }

    @Test
    fun longSingleSparseAndDenseValuesSupportDecreasingRepeatedAndRandomReads() {
        val longValues =
            listOf(
                UiText.Literal("AB🙂日".repeat(3276) + "ABCD").withFont(firstFont),
                UiText.Concatenated((0 until 256).map { index -> UiText.Literal("A".repeat(64)).withFont(if (index % 8 == 0) secondFont else firstFont) }),
                UiText.Concatenated((0 until 16384).map { index -> UiText.Literal(if (index % 3 == 0) "🙂" else "A").withFont(if (index % 2 == 0) firstFont else secondFont) }),
            )
        val random = Random(228L)
        for (text in longValues) {
            val actual = MinecraftTextContent.create(text)
            val reference = MinecraftDenseTextContentReference.create(text)
            val offsets = boundaries(reference.value)
            for (offset in offsets.dropLast(1).asReversed()) assertEquals(reference.fontAt(offset), actual.fontAt(offset))
            for (index in 0 until 2048) {
                val offset = offsets[random.nextInt(offsets.size - 1)]
                repeat(3) { assertEquals(reference.fontAt(offset), actual.fontAt(offset)) }
                val first = offsets[random.nextInt(offsets.size)]
                val last = offsets[random.nextInt(offsets.size)]
                assertEquals(reference.slice(minOf(first, last), maxOf(first, last)), actual.slice(minOf(first, last), maxOf(first, last)))
            }
            assertEquals(reference.slice(0, reference.value.length), actual.slice(0, actual.value.length))
        }
    }

    @Test
    fun strictEquivalenceRetainsOriginalStructureAndInheritedSelectionForNonemptyValues() {
        val forms =
            listOf(
                UiText.Literal(""),
                UiText.Literal("A"),
                UiText.Literal("A").withFont(firstFont),
                UiText.Literal("A").withFont(secondFont),
                UiText.concat(UiText.Literal("A"), UiText.Literal("")),
                UiText.concat(UiText.Literal(""), UiText.Literal("A")).withFont(firstFont),
                UiText.concat(UiText.Literal("").withFont(secondFont), UiText.Literal("A").withFont(firstFont)),
            )
        for (first in forms) {
            for (second in forms) {
                for (inherited in listOf(firstFont, secondFont)) {
                    val actual = MinecraftTextContent.create(first, inherited)
                    val reference = MinecraftDenseTextContentReference.create(first, inherited)
                    for (replacementInherited in listOf(firstFont, secondFont)) {
                        val replacement = MinecraftTextContent.create(second, replacementInherited)
                        val original = MinecraftDenseTextContentReference.create(second, replacementInherited)
                        assertEquals(reference.equivalentTo(original), actual.equivalentTo(replacement))
                    }
                }
            }
        }
        val wrapped = UiText.concat(UiText.Literal(""), UiText.Literal("A").withFont(firstFont))
        assertFalse(MinecraftTextContent.create(wrapped, firstFont).equivalentTo(MinecraftTextContent.create(wrapped, secondFont)))
    }

    @Test
    fun invalidOffsetsAndSplitSurrogatesKeepOriginalFailures() {
        for (text in listOf(UiText.Literal(""), UiText.Literal("A🙂B").withFont(firstFont))) {
            val actual = MinecraftTextContent.create(text)
            val reference = MinecraftDenseTextContentReference.create(text)
            val invalid = listOf(Int.MIN_VALUE, -1, 2, 4, 5, Int.MAX_VALUE)
            for (offset in invalid) {
                val original = runCatching { reference.fontAt(offset) }
                val candidate = runCatching { actual.fontAt(offset) }
                assertEquals(original.getOrNull(), candidate.getOrNull())
                assertEquals(original.exceptionOrNull()?.message, candidate.exceptionOrNull()?.message)
                for (end in invalid + listOf(0, 1, 3)) {
                    val originalSlice = runCatching { reference.slice(offset, end) }
                    val candidateSlice = runCatching { actual.slice(offset, end) }
                    assertEquals(originalSlice.getOrNull(), candidateSlice.getOrNull())
                    assertEquals(originalSlice.exceptionOrNull()?.message, candidateSlice.exceptionOrNull()?.message)
                }
            }
        }
    }

    @Test
    fun fullConstructionRejectsInvisibleMalformedFormattingAndUnresolvedTails() {
        val prefix = UiText.Literal("A".repeat(16384)).withFont(firstFont)
        val malformed = listOf("\uD800", "\uDC00", "A\uD800B", "A\uDC00B", "A§B")
        for (tail in malformed) {
            for (multiline in listOf(false, true)) compareConstructionFailure(UiText.concat(prefix, UiText.Literal(tail).withFont(secondFont)), multiline)
        }
        for (tail in listOf(UiText.Translated("test.unresolved"), UiText.Platform(UnresolvedPayload))) {
            compareConstructionFailure(UiText.concat(prefix, tail), false)
            compareConstructionFailure(UiText.concat(prefix, tail), true)
        }
        for (breakValue in listOf("\n", "\r", "\r\n", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")) {
            compareConstructionFailure(UiText.concat(prefix, UiText.Literal(breakValue)), false)
            val text = UiText.concat(prefix, UiText.Literal(breakValue).withFont(secondFont))
            val actual = MinecraftTextContent.create(text, multiline = true)
            val reference = MinecraftDenseTextContentReference.create(text, multiline = true)
            assertEquals(reference.value, actual.value)
            assertEquals(reference.slice(0, reference.value.length), actual.slice(0, actual.value.length))
        }
    }

    @Test
    fun snapshotsStayIndependentAfterReplacementCallerMutationAndConstructionFailure() {
        val callerParts = mutableListOf<UiText>(UiText.Literal("A").withFont(firstFont), UiText.Literal("🙂B").withFont(secondFont))
        val originalText = UiText.Concatenated(callerParts)
        val oldContent = MinecraftTextContent.create(originalText)
        val oldSlice = oldContent.slice(0, oldContent.value.length)
        val oldStarts = starts(oldContent).copyOf()
        val oldFonts = fonts(oldContent).toList()
        callerParts.clear()
        callerParts.add(UiText.Literal("replacement"))
        repeat(100) { index ->
            val replacement = MinecraftTextContent.create(UiText.Literal("C".repeat(index)).withFont(firstFont))
            assertNotSame(starts(oldContent), starts(replacement))
            assertEquals(originalText, oldContent.text)
            assertEquals("A🙂B", oldContent.value)
            assertEquals(oldSlice, oldContent.slice(0, 4))
            assertArrayEquals(oldStarts, starts(oldContent))
            assertEquals(oldFonts, fonts(oldContent))
        }
        compareConstructionFailure(UiText.Literal("A\uD800"), true)
        assertEquals(oldSlice, oldContent.slice(0, 4))
        assertSame(originalText, oldContent.text)
    }

    @Test
    fun exactRetainedMembershipIsBoundedByNonemptyCoalescedRuns() {
        val detachedTypes = setOf(UiText::class.java, String::class.java, IntArray::class.java, List::class.java, ResourceId::class.java)
        for (field in MinecraftTextContent::class.java.declaredFields.filter { Modifier.isStatic(it.modifiers).not() }) {
            assertTrue(field.type in detachedTypes, "Content must retain only current detached text, font identifiers and bounded provenance: " + field.name)
        }
        for (length in listOf(0, 1, 8, 128, 16384)) {
            val one = MinecraftTextContent.create(UiText.Literal("A".repeat(length)).withFont(firstFont))
            assertEquals(if (length == 0) 0 else 1, fonts(one).size)
            assertEquals(0, starts(one).size)
            if (length == 0) continue
            val alternating = UiText.Concatenated((0 until length).map { index -> UiText.Literal("A").withFont(if (index % 2 == 0) firstFont else secondFont) })
            val dense = MinecraftTextContent.create(alternating)
            assertEquals(length, fonts(dense).size)
            assertArrayEquals(IntArray(length - 1) { it + 1 }, starts(dense))
            val reference = MinecraftDenseTextContentReference.create(alternating)
            for (offset in 0 until length) assertEquals(reference.fontAt(offset), dense.fontAt(offset))
        }
        val supplementary = MinecraftTextContent.create(UiText.concat(UiText.Literal("🙂").withFont(firstFont), UiText.Literal("日").withFont(secondFont)))
        assertArrayEquals(intArrayOf(2), starts(supplementary))
    }

    private fun compareEverySlice(text: UiText) {
        val actual = MinecraftTextContent.create(text, firstFont, multiline = true)
        val reference = MinecraftDenseTextContentReference.create(text, firstFont, multiline = true)
        assertSame(text, actual.text)
        assertEquals(reference.value, actual.value)
        val offsets = boundaries(reference.value)
        for (offset in offsets.dropLast(1)) assertEquals(reference.fontAt(offset), actual.fontAt(offset))
        if (reference.value.isEmpty()) assertEquals(reference.fontAt(0), actual.fontAt(0))
        for (start in offsets) {
            for (end in offsets.filter { start <= it }) assertEquals(reference.slice(start, end), actual.slice(start, end))
        }
    }

    private fun compareConstructionFailure(
        text: UiText,
        multiline: Boolean,
    ) {
        val reference = assertThrows(IllegalArgumentException::class.java) { MinecraftDenseTextContentReference.create(text, firstFont, multiline) }
        val actual = assertThrows(IllegalArgumentException::class.java) { MinecraftTextContent.create(text, firstFont, multiline) }
        assertEquals(reference.message, actual.message)
    }

    private fun boundaries(value: String): List<Int> =
        buildList {
            var offset = 0
            add(offset)
            while (offset < value.length) {
                offset += Character.charCount(value.codePointAt(offset))
                add(offset)
            }
        }

    private fun starts(content: MinecraftTextContent): IntArray =
        MinecraftTextContent::class.java.getDeclaredField("runStarts").let { field ->
            field.isAccessible = true
            field.get(content) as IntArray
        }

    private fun fonts(content: MinecraftTextContent): List<*> =
        MinecraftTextContent::class.java.getDeclaredField("fonts").let { field ->
            field.isAccessible = true
            field.get(content) as List<*>
        }

    private data object UnresolvedPayload : PlatformText
}
