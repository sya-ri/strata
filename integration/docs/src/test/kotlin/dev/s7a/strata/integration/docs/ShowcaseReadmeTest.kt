package dev.s7a.strata.integration.docs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

/**
 * Verifies strict byte-level README anchor parsing and outside-byte preservation.
 */
internal class ShowcaseReadmeTest {
    @Test
    fun replacementPreservesPrefixAndSuffixBytes() {
        val source = "prefix\n<!-- strata-component-showcase:start -->\nold\n<!-- strata-component-showcase:end -->\nsuffix\n".toByteArray(StandardCharsets.UTF_8)
        val replacement = "new\n".toByteArray(StandardCharsets.UTF_8)

        val interior = ShowcaseReadme.interior(source)
        val result = ShowcaseReadme.replace(source, replacement)

        assertArrayEquals("\nold\n".toByteArray(StandardCharsets.UTF_8), interior)
        assertArrayEquals(
            "prefix\n<!-- strata-component-showcase:start -->\nnew\n<!-- strata-component-showcase:end -->\nsuffix\n".toByteArray(StandardCharsets.UTF_8),
            result,
        )
    }

    @Test
    fun rejectsBomCrDuplicateAndEmbeddedAnchors() {
        val valid = "<!-- strata-component-showcase:start -->\nvalue\n<!-- strata-component-showcase:end -->\n"
        val cases =
            listOf(
                "\uFEFF$valid",
                valid.replace("\nvalue", "\r\nvalue"),
                "$valid<!-- strata-component-showcase:start -->\n",
                "prefix <!-- strata-component-showcase:start -->\nvalue\n<!-- strata-component-showcase:end -->\n",
            )

        cases.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseReadme.validate(value.toByteArray(StandardCharsets.UTF_8))
            }
        }
    }

    @Test
    fun rejectsMalformedUtf8AndUnorderedAnchors() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        val malformedFailure = assertThrows(IllegalArgumentException::class.java) { ShowcaseReadme.validate(malformed) }
        assertEquals("README must contain valid UTF-8.", malformedFailure.message)
        assertTrue(malformedFailure.cause is CharacterCodingException)

        val unordered = "<!-- strata-component-showcase:end -->\nvalue\n<!-- strata-component-showcase:start -->\n"
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                ShowcaseReadme.validate(unordered.toByteArray(StandardCharsets.UTF_8))
            }
        assertEquals("README anchors must be ordered.", failure.message)
    }
}
