package dev.s7a.strata.integration.docs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * Extracts marker-delimited source regions from compiled showcase examples.
 *
 * Extraction normalizes CRLF and CR line endings to LF and never writes the source tree.
 */
internal object ShowcaseSources {
    private const val BEGIN_PREFIX = "// showcase-source-begin:"
    private const val END_PREFIX = "// showcase-source-end:"

    /**
     * Reads one exact source region for a typed page slug.
     *
     * @param source typed source reference containing the marker pair.
     * @param sourceRoot source root containing the referenced file.
     * @return an immutable region containing the normalized source and slug.
     * @throws IllegalArgumentException when the file, encoding, or marker contract is invalid.
     */
    internal fun extract(
        source: SourceReference,
        sourceRoot: Path,
    ): SourceRegion {
        val sourceFile = source.resolve(sourceRoot)
        val slug = source.slug
        val bytes = Files.readAllBytes(sourceFile)
        val hasBom =
            3 <= bytes.size &&
                bytes[0].toInt() and 0xFF == 0xEF &&
                bytes[1].toInt() and 0xFF == 0xBB &&
                bytes[2].toInt() and 0xFF == 0xBF
        require(hasBom.not()) {
            "Showcase source must not contain a UTF-8 BOM: $sourceFile"
        }
        val decoded = decode(bytes, sourceFile)
        val normalized =
            decoded
                .replace("\r\n", "\n")
                .replace('\r', '\n')
        val lines = normalized.split('\n')
        val begin = "$BEGIN_PREFIX$slug"
        val end = "$END_PREFIX$slug"
        require(slug.matches(Regex("[a-z][a-z0-9-]*"))) { "Showcase source slug is malformed: $slug" }
        val markerLines =
            lines.filter { line ->
                val trimmed = line.trimStart()
                line.startsWith(BEGIN_PREFIX) || line.startsWith(END_PREFIX) || trimmed.startsWith(BEGIN_PREFIX) || trimmed.startsWith(END_PREFIX)
            }
        require(markerLines.size == 2) { "Showcase source must contain exactly two marker lines for $slug." }
        val beginIndices = lines.mapIndexedNotNull { index, line -> index.takeIf { line == begin } }
        val endIndices = lines.mapIndexedNotNull { index, line -> index.takeIf { line == end } }
        require(beginIndices.size == 1) { "Showcase source must contain exactly one begin marker for $slug." }
        require(endIndices.size == 1) { "Showcase source must contain exactly one end marker for $slug." }
        val beginIndex = beginIndices.single()
        val endIndex = endIndices.single()
        require(beginIndex < endIndex) { "Showcase source markers are out of order for $slug." }
        val body = lines.subList(beginIndex + 1, endIndex)
        require(body.isNotEmpty() && body.first().isNotBlank() && body.last().isNotBlank()) {
            "Showcase source region has blank boundary lines for $slug."
        }
        require(body.any { line -> line.trimStart().startsWith("import ") }) {
            "Showcase source region must contain imports for $slug."
        }
        require(body.any { line -> line.trimStart().startsWith("internal fun ") }) {
            "Showcase source region must contain a callable example for $slug."
        }
        require(
            body.none { line ->
                val trimmed = line.trim()
                trimmed.startsWith("```")
            },
        ) {
            "Showcase source region contains a code-fence terminator for $slug."
        }
        return SourceRegion(slug, body.joinToString("\n"))
    }

    private fun decode(
        bytes: ByteArray,
        sourceFile: Path,
    ): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
        decoder.onMalformedInput(CodingErrorAction.REPORT)
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("Showcase source is not valid UTF-8: $sourceFile", error)
        }
    }
}
