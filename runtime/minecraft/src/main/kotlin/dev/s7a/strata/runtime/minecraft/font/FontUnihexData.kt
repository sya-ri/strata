package dev.s7a.strata.runtime.minecraft.font

import java.io.ByteArrayInputStream
import java.io.InputStream
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
     * Owns bounded streaming archive validation and sparse metadata decoding.
     */
    companion object {
        private const val MAX_RECORD_BYTES = 6 + 1 + 128

        /**
         * Decodes LF-separated uppercase hexadecimal records without materializing expanded entries or complete strings.
         * Every archive entry consumes entry and decompression budgets, including ignored files and directories.
         *
         * @param bytes complete caller-owned encoded ZIP data.
         * @param budget loader-owned aggregate counters; duplicate scalar records also consume capacity.
         * @return detached source rows without eagerly allocating RGBA glyph images.
         * @throws Throwable when an entry, record, or loading ceiling is invalid.
         */
        fun load(
            bytes: ByteArray,
            budget: FontLoadBudget,
        ): FontUnihexData {
            requireFontLimit(bytes.size.toLong(), budget.limits.maxAssetBytes.toLong(), "Unihex archive bytes")
            requireFontLimit(bytes.size.toLong(), budget.limits.maxArchiveBytes, "compressed Unihex archive bytes")
            return load(ByteArrayInputStream(bytes), budget)
        }

        /**
         * Consumes and closes an already encoded-byte-bounded ZIP stream on success and every failure path.
         * Only loader-owned sparse glyph rows survive this operation.
         *
         * @param input caller-transferred stream whose encoded-byte boundary has already been checked.
         * @param budget loader-owned aggregate counters.
         * @return immutable sparse rows independent of the closed input.
         * @throws Throwable when decoding or a ceiling check fails.
         */
        fun load(
            input: InputStream,
            budget: FontLoadBudget,
        ): FontUnihexData {
            val glyphs = LinkedHashMap<Int, FontHexGlyph>()
            val buffer = ByteArray(8_192)
            ZipInputStream(input).use { zip ->
                var entries = 0L
                var entry = zip.nextEntry
                while (entry != null) {
                    budget.claim(FontLoadBudget.Kind.SourceEntries, 1)
                    requireFontLimit(++entries, budget.limits.maxSourceEntries.toLong(), "Unihex archive entries")
                    requireFontLimit(entry.name.length.toLong(), budget.limits.maxPathLength.toLong(), "Unihex entry name")
                    requireFontLimit(entry.size, budget.limits.maxDecompressedEntryBytes, "Unihex expanded entry bytes")
                    readEntry(zip, budget, buffer, if (entry.isDirectory.not() && entry.name.endsWith(".hex")) glyphs else null)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            return FontUnihexData(glyphs)
        }

        private fun readEntry(
            zip: ZipInputStream,
            budget: FontLoadBudget,
            buffer: ByteArray,
            glyphs: MutableMap<Int, FontHexGlyph>?,
        ) {
            val record = ByteArray(MAX_RECORD_BYTES)
            var length = 0
            var expanded = 0L
            while (true) {
                val remaining = minOf(budget.limits.maxDecompressedEntryBytes - expanded, budget.remaining(FontLoadBudget.Kind.DecompressedBytes))
                val requested = if (buffer.size.toLong() <= remaining) buffer.size else remaining.toInt() + 1
                val count = zip.read(buffer, 0, requested)
                if (count < 0) break
                budget.claim(FontLoadBudget.Kind.DecompressedBytes, count.toLong())
                requireFontLimit(count.toLong(), budget.limits.maxDecompressedEntryBytes - expanded, "Unihex expanded entry bytes")
                expanded += count
                if (glyphs != null) {
                    for (index in 0 until count) {
                        val value = buffer[index]
                        if (value == '\n'.code.toByte()) {
                            readRecord(record, length, glyphs, budget)
                            length = 0
                        } else {
                            requireFontLimit(length.toLong() + 1, MAX_RECORD_BYTES.toLong(), "Unihex record bytes")
                            record[length++] = value
                        }
                    }
                }
            }
            if (glyphs != null && 0 < length) readRecord(record, length, glyphs, budget)
        }

        private fun readRecord(
            record: ByteArray,
            length: Int,
            glyphs: MutableMap<Int, FontHexGlyph>,
            budget: FontLoadBudget,
        ) {
            val separator = (0 until length).firstOrNull { index -> record[index] == ':'.code.toByte() } ?: -1
            require(separator in 4..6) { "Unihex lines require a four-to-six digit scalar." }
            var codePoint = 0
            for (index in 0 until separator) codePoint = (codePoint shl 4) or hexDigit(record[index])
            FontJson.validateScalar(codePoint)
            val dataLength = length - separator - 1
            require(dataLength in 32..128 && dataLength % 32 == 0) { "Unihex rows must be 8, 16, 24, or 32 pixels wide." }
            budget.claim(FontLoadBudget.Kind.Glyphs, 1)
            budget.claim(FontLoadBudget.Kind.GlyphRowBytes, 16L * Long.SIZE_BYTES)
            val rowDigits = dataLength / 16
            val rows =
                LongArray(16) { row ->
                    var value = 0L
                    repeat(rowDigits) { digit -> value = (value shl 4) or hexDigit(record[separator + 1 + row * rowDigits + digit]).toLong() }
                    value
                }
            glyphs[codePoint] = FontHexGlyph(rowDigits * 4, rows)
        }

        private fun hexDigit(value: Byte): Int =
            when (val character = value.toInt()) {
                in '0'.code..'9'.code -> character - '0'.code
                in 'A'.code..'F'.code -> character - 'A'.code + 10
                else -> throw IllegalArgumentException("Unihex values require uppercase hexadecimal digits.")
            }
    }
}
