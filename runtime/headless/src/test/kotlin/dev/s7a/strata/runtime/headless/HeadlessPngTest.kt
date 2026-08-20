package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.zip.Inflater

/**
 * Verifies deterministic PNG structure, checksums, and stored-block boundaries.
 */
internal class HeadlessPngTest {
    @Test
    fun transparentOneByOnePngHasTheKnownDeterministicBytes() {
        val png = rasterizeHeadless(emptyList(), IntSize(1, 1)).encodePng()

        assertEquals(
            "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489" +
                "00000010494441547801010500faff000000000000050001647895380000000049454e44ae426082",
            png.toHex(),
        )
    }

    @Test
    fun pngContainsOnlyCanonicalChunksAndValidChecksums() {
        val png = rasterizeHeadless(emptyList(), IntSize(2, 2)).encodePng()
        val chunks = parseChunks(png)

        assertEquals(
            listOf(PngChunkType.IHDR, PngChunkType.IDAT, PngChunkType.IEND),
            chunks.map { chunk -> chunk.type },
        )
        assertEquals(byteArrayOf(0, 0, 0, 2, 0, 0, 0, 2, 8, 6, 0, 0, 0).toList(), chunks[0].payload.toList())
        assertEquals(0x72B60D24.toInt(), chunks[0].payloadCrc)
        chunks.forEach { chunk -> assertTrue(chunk.crcValid) }
        assertEquals(0x78, chunks[1].payload[0].toInt() and 0xFF)
        assertEquals(0x01, chunks[1].payload[1].toInt() and 0xFF)
    }

    @Test
    fun storedBlocksSplitAtTheExact65535Boundary() {
        val exact = rasterizeHeadless(emptyList(), IntSize(1, 13_107)).encodePng()
        val split = rasterizeHeadless(emptyList(), IntSize(1, 13_108)).encodePng()

        val exactBlocks = storedBlocks(parseChunks(exact).single { chunk -> chunk.type === PngChunkType.IDAT }.payload)
        val splitBlocks = storedBlocks(parseChunks(split).single { chunk -> chunk.type === PngChunkType.IDAT }.payload)
        assertEquals(listOf(65_535), exactBlocks)
        assertEquals(listOf(65_535, 5), splitBlocks)
    }

    @Test
    fun pngOutputAndDigestAreFreshAndRepeatable() {
        val image = rasterizeHeadless(emptyList(), IntSize(2, 1))
        val first = image.encodePng()
        val second = image.encodePng()

        assertTrue(first !== second)
        assertEquals(first.toHex(), second.toHex())
        assertEquals(sha256(first), sha256(second))
    }

    @Test
    fun oneByOnePayloadHasFilterZeroRgbaAndKnownAdler() {
        val png = rasterizeHeadless(emptyList(), IntSize(1, 1)).encodePng()
        val idat = parseChunks(png).single { chunk -> chunk.type === PngChunkType.IDAT }.payload
        val inflater = Inflater()
        try {
            inflater.setInput(idat)
            val raw = ByteArray(5)
            assertEquals(5, inflater.inflate(raw))
            assertTrue(inflater.finished())
            assertEquals(0x00050001, readInt(idat, idat.size - 4))
            assertEquals(listOf(0, 0, 0, 0, 0), raw.map { value -> value.toInt() })
        } finally {
            inflater.end()
        }
        assertEquals(
            "eacb1a012fc0820bb358ea06380857fd97d62a420932142014ac89bcc4afbbc3",
            sha256(png),
        )
    }

    @Test
    fun multiRowPartialAlphaPayloadInflatesToIndependentFilterZeroRgbaBytes() {
        val image =
            rasterizeHeadless(
                listOf(
                    fill(IntRect(0, 0, 1, 1), 0x80402010.toInt()),
                    fill(IntRect(1, 0, 2, 1), 0xC01080F0.toInt()),
                    fill(IntRect(0, 1, 1, 2), 0x4000FF20),
                    fill(IntRect(1, 1, 2, 2), 0xE0ABCDEF.toInt()),
                ),
                IntSize(2, 2),
            )
        val png = image.encodePng()
        val idat = parseChunks(png).single { chunk -> chunk.type === PngChunkType.IDAT }.payload
        val inflater = Inflater()
        try {
            inflater.setInput(idat)
            val raw = ByteArray(18)
            assertEquals(18, inflater.inflate(raw))
            assertTrue(inflater.finished())
            assertEquals(
                listOf(
                    0,
                    0x40,
                    0x20,
                    0x10,
                    0x80,
                    0x10,
                    0x80,
                    0xF0,
                    0xC0,
                    0,
                    0x00,
                    0xFF,
                    0x20,
                    0x40,
                    0xAB,
                    0xCD,
                    0xEF,
                    0xE0,
                ),
                raw.map { value -> value.toInt() and 0xFF },
            )
        } finally {
            inflater.end()
        }
    }

    private fun parseChunks(png: ByteArray): List<PngChunk> {
        var offset = 8
        val chunks = ArrayList<PngChunk>()
        while (offset < png.size) {
            val length = readInt(png, offset)
            offset += 4
            val typeBytes = png.copyOfRange(offset, offset + 4)
            val type = chunkType(typeBytes)
            offset += 4
            val payload = png.copyOfRange(offset, offset + length)
            offset += length
            val expectedCrc = readInt(png, offset)
            offset += 4
            val actualCrc = crc32(typeBytes, payload)
            chunks += PngChunk(type, payload, expectedCrc, expectedCrc == actualCrc)
        }
        return chunks
    }

    private fun fill(
        bounds: IntRect,
        color: Int,
    ): DrawCommand.FillRectangle = DrawCommand.FillRectangle(bounds, ArgbColor(color))

    private fun chunkType(bytes: ByteArray): PngChunkType =
        when {
            bytes.contentEquals(byteArrayOf(0x49, 0x48, 0x44, 0x52)) -> PngChunkType.IHDR
            bytes.contentEquals(byteArrayOf(0x49, 0x44, 0x41, 0x54)) -> PngChunkType.IDAT
            bytes.contentEquals(byteArrayOf(0x49, 0x45, 0x4E, 0x44)) -> PngChunkType.IEND
            else -> error("Unexpected PNG chunk type.")
        }

    private fun storedBlocks(payload: ByteArray): List<Int> {
        var offset = 2
        val blocks = ArrayList<Int>()
        var finalBlock = false
        while (finalBlock.not()) {
            finalBlock = payload[offset].toInt() and 0x01 == 1
            offset += 1
            val length = (payload[offset].toInt() and 0xFF) or ((payload[offset + 1].toInt() and 0xFF) shl 8)
            val complement = (payload[offset + 2].toInt() and 0xFF) or ((payload[offset + 3].toInt() and 0xFF) shl 8)
            assertEquals(0xFFFF xor length, complement)
            offset += 4 + length
            blocks += length
        }
        return blocks
    }

    private fun readInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF shl 24) or
            (bytes[offset + 1].toInt() and 0xFF shl 16) or
            (bytes[offset + 2].toInt() and 0xFF shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun crc32(
        type: ByteArray,
        payload: ByteArray,
    ): Int {
        var crc = -1
        (type + payload).forEach { value ->
            crc = crc xor (value.toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 == 1) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1
            }
        }
        return crc.inv()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString(separator = "") { value -> "%02x".format(value.toInt() and 0xFF) }

    private data class PngChunk(
        val type: PngChunkType,
        val payload: ByteArray,
        val payloadCrc: Int,
        val crcValid: Boolean,
    )

    private enum class PngChunkType {
        IHDR,
        IDAT,
        IEND,
    }
}
