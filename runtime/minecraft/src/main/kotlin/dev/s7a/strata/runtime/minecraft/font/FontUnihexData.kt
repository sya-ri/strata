package dev.s7a.strata.runtime.minecraft.font

import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.zip.ZipInputStream

/**
 * Immutable sparse Unicode-to-hex-row index; it retains no archive or rasterized glyph cache.
 */
internal class FontUnihexData private constructor(
    glyphs: Map<Int, FontHexGlyph>,
) {
    private val glyphs = Collections.unmodifiableMap(LinkedHashMap(glyphs))

    /**
     * Returns source rows for one scalar, or null when the archive provides no glyph.
     */
    fun glyph(codePoint: Int): FontHexGlyph? = glyphs[codePoint]

    /**
     * Owns eager archive validation and sparse metadata decoding.
     */
    companion object {
        /**
         * Decodes every uppercase hexadecimal entry with LF-separated records into immutable sparse source rows.
         *
         * @param bytes complete caller-owned ZIP data.
         * @return detached source rows without eagerly allocating RGBA glyph images.
         * @throws IllegalArgumentException when a line has an invalid scalar or row width.
         * @throws Throwable when the archive cannot be read.
         */
        fun load(bytes: ByteArray): FontUnihexData {
            val glyphs = LinkedHashMap<Int, FontHexGlyph>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.isDirectory.not() && entry.name.endsWith(".hex")) {
                        readLines(zip.readBytes(), glyphs)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return FontUnihexData(glyphs)
        }

        private fun readLines(
            bytes: ByteArray,
            glyphs: MutableMap<Int, FontHexGlyph>,
        ) {
            val contents = bytes.toString(Charsets.US_ASCII)
            if (contents.isEmpty()) return
            for (line in contents.removeSuffix("\n").splitToSequence('\n')) {
                val separator = line.indexOf(':')
                require(separator in 4..6) { "Unihex lines require a four-to-six digit scalar." }
                val scalar = line.substring(0, separator)
                val data = line.substring(separator + 1)
                require(scalar.all(::hexDigit) && data.all(::hexDigit)) { "Unihex values require uppercase hexadecimal digits." }
                val codePoint = scalar.toInt(16)
                FontJson.validateScalar(codePoint)
                require(data.length in setOf(32, 64, 96, 128)) { "Unihex rows must be 8, 16, 24, or 32 pixels wide." }
                val rowDigits = data.length / 16
                val rows = LongArray(16) { row -> data.substring(row * rowDigits, (row + 1) * rowDigits).toLong(16) }
                glyphs[codePoint] = FontHexGlyph(rowDigits * 4, rows)
            }
        }

        private fun hexDigit(value: Char): Boolean = value in '0'..'9' || value in 'A'..'F'
    }
}
