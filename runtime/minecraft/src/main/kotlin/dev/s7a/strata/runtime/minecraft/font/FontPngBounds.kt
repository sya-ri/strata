package dev.s7a.strata.runtime.minecraft.font

import java.util.zip.Inflater

/**
 * PNG allocation preflight that retains no decoded image and bounds DEFLATE output with one fixed scratch buffer.
 * Encoded input remains caller-owned; all inflater state is released synchronously, including malformed or oversized input.
 */
internal object FontPngBounds {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    /**
     * Validates recognizable PNG input and reports observed pixel and inflated payload before accepting it.
     * The callback may reject aggregate work, and failures never retain input or inflater state.
     *
     * @param bytes bounded encoded input, read without copying.
     * @param limits inclusive per-image and expansion limits.
     * @param consume synchronous aggregate accounting callback, invoked before per-input rejection.
     * @return true for PNG input or false when its signature is absent.
     * @throws IllegalArgumentException when recognizable PNG input is malformed or exceeds a ceiling.
     */
    fun check(
        bytes: ByteArray,
        limits: MinecraftFontLoadLimits,
        consume: (Long) -> Unit,
    ): Boolean {
        requireFontLimit(bytes.size.toLong(), limits.maxAssetBytes.toLong(), "encoded PNG bytes")
        if (bytes.size < signature.size || signature.indices.any { index -> bytes[index] != signature[index] }) return false
        var first = chunk(bytes, signature.size)
        val rawDeflate = first.kind === Kind.AppleCgbi
        if (rawDeflate) first = chunk(bytes, first.end)
        require(first.kind === Kind.Header && first.length == 13) { "PNG IHDR must precede image data." }
        val width = dimension(bytes, first.data)
        val height = dimension(bytes, first.data + 4)
        val pixels = width.toLong() * height
        val payload = if (Long.MAX_VALUE / 4 < pixels) Long.MAX_VALUE else pixels * 4
        val work = Work(limits, consume)
        work.claim(payload)
        limits.requireImageSize(width, height)
        val inflater = Inflater(rawDeflate)
        try {
            scan(bytes, first.end, inflater, work, limits)
        } finally {
            inflater.end()
        }
        return true
    }

    private fun scan(
        bytes: ByteArray,
        start: Int,
        inflater: Inflater,
        work: Work,
        limits: MinecraftFontLoadLimits,
    ) {
        val expansion = Expansion(inflater, work, limits)
        var offset = start
        while (offset < bytes.size) {
            val next = chunk(bytes, offset)
            when (next.kind) {
                Kind.ImageData -> {
                    expansion.accept(bytes, next.data, next.length)
                }

                Kind.End -> {
                    require(next.length == 0 && inflater.finished()) { "PNG image data is incomplete." }
                    return
                }

                Kind.Header, Kind.AppleCgbi -> {
                    throw IllegalArgumentException("PNG contains a repeated header.")
                }

                Kind.Other -> {}
            }
            offset = next.end
        }
        throw IllegalArgumentException("PNG IEND is missing.")
    }

    private fun chunk(
        bytes: ByteArray,
        offset: Int,
    ): Chunk {
        require(0 <= offset && offset.toLong() + 12 <= bytes.size) { "PNG chunk header is truncated." }
        val length = unsignedInt(bytes, offset)
        val end = offset.toLong() + 12 + length
        require(end <= bytes.size) { "PNG chunk payload is truncated." }
        return Chunk(Kind.decode(unsignedInt(bytes, offset + 4).toInt()), offset + 8, length.toInt(), end.toInt())
    }

    private fun dimension(
        bytes: ByteArray,
        offset: Int,
    ): Int {
        val value = unsignedInt(bytes, offset)
        require(value in 1..Int.MAX_VALUE.toLong()) { "PNG dimensions must be positive signed integers." }
        return value.toInt()
    }

    private fun unsignedInt(
        bytes: ByteArray,
        offset: Int,
    ): Long {
        var value = 0L
        repeat(4) { index -> value = (value shl 8) or (bytes[offset + index].toLong() and 0xff) }
        return value
    }

    private class Work(
        private val limits: MinecraftFontLoadLimits,
        private val consume: (Long) -> Unit,
    ) {
        private var total = 0L

        fun remaining(): Long = limits.maxDecompressedBytes - total

        fun claim(amount: Long) {
            consume(amount)
            val available = remaining()
            if (available < amount) {
                total = limits.maxDecompressedBytes
                requireFontLimit(amount, available, "PNG aggregate expanded payload")
            }
            total += amount
        }
    }

    private class Expansion(
        private val inflater: Inflater,
        private val work: Work,
        private val limits: MinecraftFontLoadLimits,
    ) {
        private val scratch = ByteArray(8 * 1024)
        private var total = 0L

        fun accept(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            if (inflater.finished()) return
            inflater.setInput(bytes, offset, length)
            while (inflater.needsInput().not() && inflater.finished().not()) {
                val capacity = minOf(scratch.size.toLong() - 1, limits.maxDecompressedEntryBytes - total, work.remaining()).toInt() + 1
                val count = inflater.inflate(scratch, 0, capacity)
                if (count == 0) {
                    require(inflater.needsInput() || inflater.finished()) { "PNG image data cannot make progress." }
                } else {
                    work.claim(count.toLong())
                    total += count
                    requireFontLimit(total, limits.maxDecompressedEntryBytes, "PNG expanded image-data bytes")
                }
            }
        }
    }

    private data class Chunk(
        val kind: Kind,
        val data: Int,
        val length: Int,
        val end: Int,
    )

    private enum class Kind(
        private val code: Int,
    ) {
        Header(0x49484452),
        ImageData(0x49444154),
        End(0x49454e44),
        AppleCgbi(0x43674249),
        Other(0),
        ;

        companion object {
            fun decode(code: Int): Kind = entries.firstOrNull { kind -> kind.code == code } ?: Other
        }
    }
}
