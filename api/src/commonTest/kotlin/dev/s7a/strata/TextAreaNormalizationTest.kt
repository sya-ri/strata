@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Compares public constructor and setter behavior against two independent references on JVM and JavaScript.
 */
internal class TextAreaNormalizationTest {
    @Test
    fun exhaustiveShortCodeUnitInputsPreserveBothReferencesAndFirstFailure() {
        val alphabet = charArrayOf('A', '日', '\u0301', '\u200D', '\u2066', '\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029', '\u0000', '\t', '\u001F', '\u007F', '\u00A7', '\uD83D', '\uDE42')
        var inputs = listOf("")
        repeat(4) { size ->
            for (input in inputs) {
                for (maximum in 1..5) verify(input, maximum)
            }
            if (size < 3) inputs = inputs.flatMap { prefix -> alphabet.map { unit -> prefix + unit } }
        }
    }

    @Test
    fun longCanonicalConvertedAndMalformedTailsPreserveReachabilityAndNormalizedBounds() {
        val pieces = listOf("A", "日", "🙂", "e\u0301", "\u2066B\u2069", "\n")
        for (length in listOf(2_048, 16_384)) {
            val canonical = buildString { repeat(length) { append(pieces[it % pieces.size]) } }
            val positions = listOf(0, 1, canonical.length / 2, canonical.length)
            val tails = listOf("", "\u0000", "\uD800", "\uDC00", "\u00A7", "AA\u0000", "🙂\uD800")
            for (separator in listOf("\n", "\r\n", "\r", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")) {
                for (position in positions) {
                    val converted = canonical.substring(0, position) + separator + canonical.substring(position)
                    for (tail in tails) {
                        for (maximum in listOf(1, canonical.length - 1, canonical.length, canonical.length + 1, canonical.length + 3)) {
                            verify(converted + tail, maximum)
                        }
                    }
                }
            }
            verify("\r\n".repeat(length), length)
            verify("\r\n".repeat(length) + "\uD800", length)
            verify("\r\n".repeat(length) + "A\uD800", length)
        }
        for (maximum in listOf(-1, 0)) verify("\uD800", maximum, setter = false)
    }

    @Test
    fun canonicalOwnershipTransferOccursOnlyAfterCompleteValidationAndEqualWritesKeepTheOldReference() {
        for (input in listOf("", "A", "日本語\n한국어", "🙂\n𐐀", "e\u0301\u200D\u2066🙂\u2069")) {
            val state = TextAreaState(input, maxLength = maxOf(1, input.length))
            assertSame(input, state.value)
            val same = input.toCharArray().concatToString()
            state.value = same
            assertSame(input, state.value)
        }
        val state = TextAreaState("before")
        val canonical = "A\n🙂".toCharArray().concatToString()
        state.value = canonical
        assertSame(canonical, state.value)
        state.value = "A\r\n🙂"
        assertSame(canonical, state.value)
        assertFailsWith<IllegalArgumentException> { state.value = canonical + "\uD800" }
        assertSame(canonical, state.value)
    }

    private fun verify(input: String, maximum: Int, setter: Boolean = true) {
        val scalar = outcome { TextAreaNormalizationReference.scalar(input, maximum) }
        assertEquals(scalar, outcome { TextAreaNormalizationReference.utf16(input, maximum) })
        assertEquals(scalar, outcome { TextAreaState(input, maximum).value })
        if (setter.not()) return
        val state = TextAreaState("", maximum)
        val previous = state.value
        val scroll = state.scrollState
        var callbacks = 0
        val release = state.observe { committed ->
            callbacks += 1
            assertSame(committed, state.value)
        }
        try {
            assertEquals(
                scalar,
                outcome {
                    state.value = input
                    state.value
                },
            )
            assertSame(scroll, state.scrollState)
            assertEquals(if (scalar.text != null && scalar.text.isNotEmpty()) 1 else 0, callbacks)
            if (scalar.failure != null) assertSame(previous, state.value)
        } finally {
            release.close()
        }
    }

    private fun outcome(operation: () -> String): Outcome =
        try {
            Outcome(text = operation())
        } catch (failure: IllegalArgumentException) {
            Outcome(failure = failure::class, message = failure.message)
        }

    /**
     * Detached observable result; reference identity is intentionally tested separately from scalar parity.
     */
    private data class Outcome(
        val text: String? = null,
        val failure: KClass<out Throwable>? = null,
        val message: String? = null,
    )
}
