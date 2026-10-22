package dev.s7a.strata

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import dev.s7a.strata.text.withFont
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies text composition retains detached ordered membership on JVM and JavaScript.
 */
internal class UiTextCompositionTest {
    @Test
    fun listAndVarargConstructionPreserveNestedUnresolvedValues() {
        val first = UiText.Literal("first")
        val font = first.withFont(ResourceId("test", "font"))
        val translated = UiText.Translated("test.label", listOf(UiTextArgument.Text(first)), TranslationFallback.UseKey)
        val nested = UiText.Concatenated(first, translated)
        val source = mutableListOf(first, font, translated, nested, first)
        val expected = source.toList()
        val values = listOf(UiText.Concatenated(source), UiText.Concatenated(*source.toTypedArray()), UiText.concat(*source.toTypedArray()))
        source.clear()

        for (value in values) {
            val composition = value as UiText.Concatenated
            assertEquals(expected, composition.parts)
            expected.forEachIndexed { index, part -> assertSame(part, composition.parts[index]) }
            assertEquals(UiText.Concatenated(expected), composition)
            assertEquals(expected.hashCode(), composition.hashCode())
            assertEquals("Concatenated(parts=$expected)", composition.toString())
            assertSame(nested, composition.parts[3])
            assertSame(first, composition.parts.last())
        }
    }

    @Test
    fun earlierCompositionsStayIndependentAcrossInputReplacement() {
        val first = UiText.Literal("first")
        val second = UiText.Literal("second")
        val source = arrayOf<UiText>(first, first)
        val original = UiText.Concatenated(*source)
        val companion = UiText.concat(*source) as UiText.Concatenated
        source[0] = second
        val replacement = UiText.Concatenated(*source)
        source[1] = second

        assertEquals(listOf(first, first), original.parts)
        assertEquals(original, companion)
        assertEquals(listOf(second, first), replacement.parts)
        assertSame(first, original.parts[0])
        assertSame(first, original.parts[1])
        assertSame(second, replacement.parts[0])
    }

    @Test
    fun emptyAndSingletonFactoriesKeepTheirExistingContracts() {
        val singleton = UiText.Translated("test.singleton", "fallback")
        assertEquals(UiText.Literal(""), UiText.concat())
        assertSame(singleton, UiText.concat(singleton))
        assertSame(singleton, UiText.Concatenated(singleton).parts.single())
        assertSame(singleton, UiText.Concatenated(listOf(singleton)).parts.single())
        val varargFailure = assertFailsWith<IllegalArgumentException> { UiText.Concatenated() }
        val listFailure = assertFailsWith<IllegalArgumentException> { UiText.Concatenated(emptyList()) }
        assertEquals("Concatenated text must contain at least one part.", varargFailure.message)
        assertEquals(varargFailure.message, listFailure.message)
    }
}
