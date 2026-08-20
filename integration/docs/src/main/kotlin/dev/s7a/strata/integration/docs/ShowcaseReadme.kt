package dev.s7a.strata.integration.docs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Pure strict byte-level parsing and replacement for the generator-owned README region.
 *
 * All bytes outside the two standalone anchors are preserved exactly.
 */
internal object ShowcaseReadme {
    private const val README_BEGIN = "<!-- strata-component-showcase:start -->"
    private const val README_END = "<!-- strata-component-showcase:end -->"

    /**
     * Validates the complete README encoding and anchor contract.
     *
     * @param source raw README bytes.
     * @throws IllegalArgumentException when encoding, line endings, anchors, or ordering are invalid.
     */
    internal fun validate(source: ByteArray) {
        anchors(source)
    }

    /**
     * Extracts the exact anchored interior bytes.
     *
     * @param source raw README bytes.
     * @return bytes between the begin-marker line and end-marker line.
     */
    internal fun interior(source: ByteArray): ByteArray {
        val anchor = anchors(source)
        return source.copyOfRange(anchor.first + README_BEGIN_BYTES.size, anchor.second)
    }

    /**
     * Replaces only the anchored interior while preserving all outside bytes.
     *
     * @param source raw README bytes.
     * @param region generated LF-terminated UTF-8 region without anchors.
     * @return updated README bytes.
     */
    internal fun replace(
        source: ByteArray,
        region: ByteArray,
    ): ByteArray {
        val anchor = anchors(source)
        validateRegion(region)
        val prefix = source.copyOfRange(0, anchor.first + README_BEGIN_BYTES.size)
        val suffix = source.copyOfRange(anchor.second, source.size)
        return prefix + byteArrayOf(LF) + region + suffix
    }

    private fun anchors(source: ByteArray): Pair<Int, Int> {
        require(source.size < Int.MAX_VALUE) { "README is too large to index safely." }
        require(hasBom(source).not()) { "README must not contain a BOM." }
        val decoded = decode(source)
        require(decoded.contains('\r').not()) { "README must use LF line endings." }
        val begins = occurrences(source, README_BEGIN_BYTES)
        val ends = occurrences(source, README_END_BYTES)
        require(begins.size == 1 && ends.size == 1) { "README anchors must be unique." }
        val begin = begins.single()
        val end = ends.single()
        require(begin < end) { "README anchors must be ordered." }
        require(standalone(source, begin, README_BEGIN_BYTES.size)) { "README begin anchor must be standalone." }
        require(standalone(source, end, README_END_BYTES.size)) { "README end anchor must be standalone." }
        require(begin + README_BEGIN_BYTES.size < end) { "README anchor interior is missing." }
        return begin to end
    }

    private fun decode(source: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
        decoder.onMalformedInput(CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(source)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("README must contain valid UTF-8.", error)
        }
    }

    private fun validateRegion(region: ByteArray) {
        require(region.isNotEmpty() && region.last() == LF) { "Generated README region must be LF-terminated." }
        require(hasBom(region).not()) { "Generated README region must not contain a BOM." }
        require(region.contains(CR).not()) { "Generated README region must not contain CR." }
        decode(region)
    }

    private fun hasBom(source: ByteArray): Boolean = 3 <= source.size && source[0] == 0xEF.toByte() && source[1] == 0xBB.toByte() && source[2] == 0xBF.toByte()

    private fun occurrences(
        source: ByteArray,
        needle: ByteArray,
    ): List<Int> {
        val result = ArrayList<Int>()
        var index = 0
        val limit = source.size - needle.size
        while (index <= limit) {
            if (matchesAt(source, needle, index)) {
                result += index
                index += needle.size
            } else {
                index += 1
            }
        }
        return result
    }

    private fun matchesAt(
        source: ByteArray,
        needle: ByteArray,
        start: Int,
    ): Boolean {
        needle.indices.forEach { offset ->
            if (source[start + offset] != needle[offset]) return false
        }
        return true
    }

    private fun standalone(
        source: ByteArray,
        start: Int,
        length: Int,
    ): Boolean {
        val before = start == 0 || source[start - 1] == LF
        val afterIndex = start + length
        val after = afterIndex == source.size || source[afterIndex] == LF
        return before && after
    }

    private val README_BEGIN_BYTES = README_BEGIN.toByteArray(StandardCharsets.UTF_8)
    private val README_END_BYTES = README_END.toByteArray(StandardCharsets.UTF_8)
    private const val LF: Byte = 10
    private const val CR: Byte = 13
}
