package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/**
 * Encodes original deterministic RGBA fixtures without Java2D, a display, or third-party image assets.
 * Every returned array belongs to the caller; compression streams close before return.
 */
internal object ShowcaseFixturePng {
    /**
     * Creates one complete unfiltered RGBA PNG from bounded test dimensions and a pure pixel callback.
     */
    fun create(
        size: IntSize,
        pixel: (Int, Int) -> Int,
    ): ByteArray {
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { stream ->
            val row = ByteArray(Math.addExact(Math.multiplyExact(size.width, 4), 1))
            for (y in 0 until size.height) {
                for (x in 0 until size.width) {
                    val color = pixel(x, y)
                    val offset = x * 4 + 1
                    row[offset] = (color ushr 16).toByte()
                    row[offset + 1] = (color ushr 8).toByte()
                    row[offset + 2] = color.toByte()
                    row[offset + 3] = (color ushr 24).toByte()
                }
                stream.write(row)
            }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeLong(0x89504E470D0A1A0AuL.toLong())
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { data ->
                data.writeInt(size.width)
                data.writeInt(size.height)
                data.write(byteArrayOf(8, 6, 0, 0, 0))
            }
            output.chunk("IHDR", header.toByteArray())
            output.chunk("IDAT", compressed.toByteArray())
            output.chunk("IEND", byteArrayOf())
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.chunk(
        type: String,
        bytes: ByteArray,
    ) {
        val name = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32().apply {
                update(name)
                update(bytes)
            }
        writeInt(bytes.size)
        write(name)
        write(bytes)
        writeInt(crc.value.toInt())
    }
}
