package dev.s7a.strata.runtime.headless

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.imageio.ImageIO

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

    @Test
    fun blittedPixelInflatesToFilterZeroRgbaBytes() {
        val source = createDrawImage(IntSize(1, 1), intArrayOf(0x80123456.toInt()))
        val image =
            rasterizeHeadless(
                listOf(DrawCommand.BlitImage(source, IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 1))),
                IntSize(1, 1),
            )
        val idat = parseChunks(image.encodePng()).single { chunk -> chunk.type === PngChunkType.IDAT }.payload
        val inflater = Inflater()
        try {
            inflater.setInput(idat)
            val raw = ByteArray(5)
            assertEquals(5, inflater.inflate(raw))
            assertTrue(inflater.finished())
            assertEquals(listOf(0, 0x12, 0x34, 0x56, 0x80), raw.map { value -> value.toInt() and 0xFF })
        } finally {
            inflater.end()
        }
    }

    @Test
    fun directOutputMatchesAnIndependentCanonicalEncoderAtRowAndBlockBoundaries() {
        val sizes =
            listOf(
                IntSize(1, 1),
                IntSize(2, 3),
                IntSize(54, 302),
                IntSize(1, 13_106),
                IntSize(1, 13_107),
                IntSize(1, 13_108),
                IntSize(2, 7_282),
                IntSize(2, 14_564),
                IntSize(4, 3_856),
                IntSize(64, 256),
                IntSize(16_384, 1),
                IntSize(126, 131),
                IntSize(127, 130),
                IntSize(128, 129),
                IntSize(129, 128),
                IntSize(3, 13_108),
            )
        sizes.forEach { size ->
            listOf(0, 127, 255).forEach { alpha ->
                val image = patternedImage(size, alpha)
                val expected = canonicalPng(size, image.copyArgb())
                val actual = image.encodePng()
                assertArrayEquals(expected, actual, "size=$size, alpha=$alpha")
                assertTrue(parseChunks(actual).all { chunk -> chunk.crcValid })
                verifyDecodedPixels(image, actual)
            }
        }
    }

    @Test
    fun fullHdOutputPreservesEveryPartialAlphaPixelAndCanonicalByte() {
        val image = patternedImage(IntSize(1920, 1080), 127)
        val encoded = image.encodePng()
        assertArrayEquals(canonicalPng(image.size, image.copyArgb()), encoded)
        verifyDecodedPixels(image, encoded)
    }

    @Test
    fun modifyingReturnedStorageCannotChangeAnotherEncodingOrSourcePixels() {
        val image = patternedImage(IntSize(128, 129), 127)
        val pixels = image.copyArgb()
        val first = image.encodePng()
        val second = image.encodePng()
        val expected = second.copyOf()
        first.fill(0)
        assertArrayEquals(expected, second)
        assertArrayEquals(expected, image.encodePng())
        assertArrayEquals(pixels, image.copyArgb())
    }

    @Test
    fun concurrentCallsKeepIndependentBlockAndChecksumState() {
        val image = patternedImage(IntSize(126, 131), 127)
        val expected = canonicalPng(image.size, image.copyArgb())
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 8).map { executor.submit<ByteArray> { image.encodePng() } }
            val outputs = futures.map { future -> future.get() }
            outputs.forEach { output -> assertArrayEquals(expected, output) }
            outputs.first().fill(0)
            outputs.drop(1).forEach { output -> assertArrayEquals(expected, output) }
            assertArrayEquals(expected, image.encodePng())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun allDerivedLengthsFailBeforeAllocatingOutputOrReadingPixels() {
        // Public image construction cannot supply these extents without allocating enormous valid rasters.
        val encoder = Class.forName("dev.s7a.strata.runtime.headless.HeadlessImplementation\$PngEncoder")
        val instance = encoder.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
        val encode = encoder.getDeclaredMethod("encode", IntSize::class.java, IntArray::class.java).apply { isAccessible = true }
        val cases =
            listOf(
                IntSize(536_870_912, 1) to "PNG row width",
                IntSize(1, 429_496_730) to "PNG scanline data",
                IntSize(536_829_953, 1) to "PNG deflate stream",
                IntSize(76_689_993, 7) to "PNG zlib stream",
                IntSize(536_829_950, 1) to "PNG chunk",
                IntSize(536_829_939, 1) to "PNG output",
            )
        cases.forEach { (size, label) ->
            val failure = assertThrows(InvocationTargetException::class.java) { encode.invoke(instance, size, IntArray(0)) }
            assertTrue(failure.cause is ArithmeticException)
            assertEquals("$label exceeds Int.MAX_VALUE.", failure.cause?.message)
        }
    }

    private fun patternedImage(
        size: IntSize,
        alpha: Int,
    ): HeadlessImage {
        val source =
            createDrawImage(
                size,
                IntArray(size.width * size.height) { index ->
                    (alpha shl 24) or (index * 73_471 and 0xFFFFFF)
                },
            )
        val bounds = IntRect(0, 0, size.width, size.height)
        return rasterizeHeadless(listOf(DrawCommand.BlitImage(source, bounds, bounds)), size)
    }

    private fun verifyDecodedPixels(
        image: HeadlessImage,
        png: ByteArray,
    ) {
        val decoded = ByteArrayInputStream(png).use { input -> checkNotNull(ImageIO.read(input)) }
        assertEquals(image.size.width, decoded.width)
        assertEquals(image.size.height, decoded.height)
        assertArrayEquals(image.copyArgb(), decoded.getRGB(0, 0, decoded.width, decoded.height, null, 0, decoded.width))
    }

    private fun canonicalPng(
        size: IntSize,
        pixels: IntArray,
    ): ByteArray {
        val scanlines = ByteArrayOutputStream()
        DataOutputStream(scanlines).use { rows ->
            pixels.forEachIndexed { index, argb ->
                if (index % size.width == 0) rows.writeByte(0)
                rows.writeByte(argb ushr 16)
                rows.writeByte(argb ushr 8)
                rows.writeByte(argb)
                rows.writeByte(argb ushr 24)
            }
        }
        val raw = scanlines.toByteArray()
        val compressed = ByteArrayOutputStream()
        DataOutputStream(compressed).use { zlib ->
            zlib.writeShort(0x7801)
            var offset = 0
            while (offset < raw.size) {
                val count = minOf(65_535, raw.size - offset)
                zlib.writeByte(if (offset + count == raw.size) 1 else 0)
                zlib.writeByte(count)
                zlib.writeByte(count ushr 8)
                zlib.writeByte(count.inv())
                zlib.writeByte(count.inv() ushr 8)
                zlib.write(raw, offset, count)
                offset += count
            }
            zlib.writeInt(Adler32().apply { update(raw) }.value.toInt())
        }
        val header = ByteArrayOutputStream()
        DataOutputStream(header).use { ihdr ->
            ihdr.writeInt(size.width)
            ihdr.writeInt(size.height)
            ihdr.write(byteArrayOf(8, 6, 0, 0, 0))
        }
        val png = ByteArrayOutputStream()
        DataOutputStream(png).use { output ->
            output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            listOf("IHDR" to header.toByteArray(), "IDAT" to compressed.toByteArray(), "IEND" to ByteArray(0)).forEach { (type, payload) ->
                val bytes = type.encodeToByteArray()
                output.writeInt(payload.size)
                output.write(bytes)
                output.write(payload)
                output.writeInt(
                    CRC32()
                        .apply {
                            update(bytes)
                            update(payload)
                        }.value
                        .toInt(),
                )
            }
        }
        return png.toByteArray()
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
