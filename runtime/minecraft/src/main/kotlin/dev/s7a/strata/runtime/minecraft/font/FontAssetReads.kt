package dev.s7a.strata.runtime.minecraft.font

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Reads an encoded font asset with an inclusive byte ceiling, checking overflow before appending bytes.
 * At most one byte beyond the ceiling is consumed to distinguish exact-size input from oversized input.
 * This synchronous operation neither retains nor closes the stream; its caller owns closing on success and failure.
 *
 * @receiver caller-owned stream whose implementation controls blocking and I/O failures.
 * @param maximumBytes non-negative payload ceiling.
 * @return a detached byte array whose length does not exceed the ceiling.
 * @throws IllegalArgumentException when the ceiling is negative or exceeded.
 * @throws Throwable when reading fails.
 */
public fun InputStream.readMinecraftFontBytes(maximumBytes: Int): ByteArray {
    require(0 <= maximumBytes) { "Font byte ceilings must be non-negative." }
    val bytes = ByteArrayOutputStream(minOf(maximumBytes, 8_192))
    val buffer = ByteArray(8_192)
    while (true) {
        val remaining = maximumBytes - bytes.size()
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining.toLong() + 1).toInt())
        if (count < 0) return bytes.toByteArray()
        if (count == 0) {
            val value = read()
            if (value < 0) return bytes.toByteArray()
            requireFontLimit(1, remaining.toLong(), "asset bytes")
            bytes.write(value)
        } else {
            requireFontLimit(count.toLong(), remaining.toLong(), "asset bytes")
            bytes.write(buffer, 0, count)
        }
    }
}
