package dev.s7a.strata

import dev.s7a.strata.resource.ResourceId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Preserves the complete namespace and path grammar, including dot-segment and Unicode rejection.
 */
internal class ResourceIdAdmissionTest {
    @Test
    fun admissionMatchesTheIndependentGrammarAcrossCharacterAndSegmentBoundaries() {
        val namespace = Regex("[a-z0-9_.-]+")
        val path = Regex("(?!\\.{1,2}(?:/|$))(?!.*?/\\.{1,2}(?:/|$))[a-z0-9._-]+(?:/[a-z0-9._-]+)*")
        val candidates = mutableListOf("", ".", "..", "...", "/", "/a", "a/", "a//b", "a/./b", "a/../b", "a/.b/..c", "a/b/.", "a/b/..", "a/b/...")
        for (value in 0..255) candidates += "a${value.toChar()}b"
        candidates += listOf("a日本語", "a🙂", "a\uD800", "a\uDC00", "a\uFFFF", "a\n", "a\r\n")
        val random = Random(27)
        val alphabet = "abz019_.-/AZ :\n"
        repeat(2_000) { candidates += List(random.nextInt(20)) { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
        for (candidate in candidates) {
            assertEquals(namespace.matches(candidate), runCatching { ResourceId(candidate, "valid") }.isSuccess, "namespace: $candidate")
            assertEquals(path.matches(candidate), runCatching { ResourceId("valid", candidate) }.isSuccess, "path: $candidate")
        }
    }
}
