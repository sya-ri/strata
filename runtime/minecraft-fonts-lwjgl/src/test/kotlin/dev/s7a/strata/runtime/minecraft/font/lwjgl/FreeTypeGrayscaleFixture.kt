package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Bitmap
import org.lwjgl.util.freetype.FreeType
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Owns native storage only until conversion returns; every retained image is inspected after release.
 * Kept outside the test class hierarchy so STB-only discovery does not resolve FreeType method signatures.
 */
internal object FreeTypeGrayscaleFixture {
    /**
     * Verifies the twenty required synthetic converter fixtures independently of any font's raster shape.
     */
    fun matrix(bytes: ByteArray) {
        val retained =
            FreeTypeMinecraftFontFace(bytes, MinecraftTrueTypeSettings()).use { face ->
                val convert = converter(face)
                listOf(1, 8, 16, 64, 256).flatMapIndexed { extentIndex, axis ->
                    listOf(axis, axis + 3, -axis, -axis - 3).mapIndexed { layoutIndex, pitch ->
                        image(face, convert, axis, axis, pitch, extentIndex * 4 + layoutIndex)
                    }
                }
            }
        retained.forEach { (actual, expected) ->
            assertArrayEquals(expected, actual.copyArgb())
            actual.copyArgb().fill(0)
            assertArrayEquals(expected, actual.copyArgb())
        }
    }

    /**
     * Uses non-square extents and all byte values so transposition, padding and signed-byte bugs fail.
     */
    fun asymmetric(bytes: ByteArray) {
        FreeTypeMinecraftFontFace(bytes, MinecraftTrueTypeSettings()).use { face ->
            val convert = converter(face)
            listOf(1 to 256, 256 to 1, 3 to 7, 17 to 19).forEachIndexed { index, (width, height) ->
                listOf(width, width + 3, -width, -width - 3).forEach { pitch ->
                    val (actual, expected) = image(face, convert, width, height, pitch, 20 + index)
                    assertEquals(IntSize(width, height), actual.size)
                    assertArrayEquals(expected, actual.copyArgb())
                }
            }
        }
    }

    /**
     * Multiple simultaneous faults prove mode, extent, stride and missing-buffer precedence.
     */
    fun rejections(bytes: ByteArray) {
        FreeTypeMinecraftFontFace(bytes, MinecraftTrueTypeSettings()).use { face ->
            val convert = converter(face)
            failure(face, convert, IntSize(2, 2), Fault(IntSize(3, 2), 0, FreeType.FT_PIXEL_MODE_MONO), IllegalArgumentException::class.java to "The ttf provider requires grayscale glyphs.")
            failure(face, convert, IntSize(2, 2), Fault(IntSize(3, 2), 0), IllegalArgumentException::class.java to "FreeType glyph dimensions changed during rasterization.")
            listOf(0, 1, -1, Int.MIN_VALUE).forEach { pitch ->
                failure(face, convert, IntSize(2, 2), Fault(IntSize(2, 2), pitch), IllegalArgumentException::class.java to "FreeType returned an invalid glyph stride.")
            }
            failure(face, convert, IntSize(2, 2), Fault(IntSize(2, 2), 2), IllegalStateException::class.java to "FreeType returned no glyph pixels.")
        }
    }

    /**
     * Checked native capacity rejects overflow before accessing an absent buffer.
     */
    fun bufferFailures(bytes: ByteArray) {
        FreeTypeMinecraftFontFace(bytes, MinecraftTrueTypeSettings()).use { face ->
            val convert = converter(face)
            failure(face, convert, IntSize(1, 2), Fault(IntSize(1, 2), Int.MAX_VALUE), ArithmeticException::class.java to "integer overflow")
            failure(face, convert, IntSize.Zero, Fault(IntSize.Zero, 0), IllegalStateException::class.java to "FreeType returned no glyph pixels.")
        }
    }

    /**
     * Keeps private nonpositive input behavior rather than imposing a new public admission rule.
     */
    fun privateExtents(bytes: ByteArray) {
        FreeTypeMinecraftFontFace(bytes, MinecraftTrueTypeSettings()).use { face ->
            val convert = converter(face)
            val buffer = MemoryUtil.memAlloc(1)
            try {
                listOf(0 to 0, 0 to 3, 3 to 0).forEach { (width, height) ->
                    withBitmap(width, height, width, FreeType.FT_PIXEL_MODE_GRAY, buffer) { bitmap ->
                        val actual = invoke(face, convert, bitmap, width, height)
                        assertEquals(IntSize(width, height), actual.size)
                        assertArrayEquals(IntArray(0), actual.copyArgb())
                    }
                }
                withBitmap(-1, 0, 0, FreeType.FT_PIXEL_MODE_GRAY, buffer) { bitmap ->
                    val actual = assertThrows(IllegalArgumentException::class.java) { invoke(face, convert, bitmap, -1, 0) }
                    assertEquals("Width must be non-negative.", actual.message)
                }
                withBitmap(-1, 1, 0, FreeType.FT_PIXEL_MODE_GRAY, buffer) { bitmap ->
                    assertThrows(NegativeArraySizeException::class.java) { invoke(face, convert, bitmap, -1, 1) }
                }
                withBitmap(-1, -1, 0, FreeType.FT_PIXEL_MODE_GRAY, buffer) { bitmap ->
                    assertThrows(IndexOutOfBoundsException::class.java) { invoke(face, convert, bitmap, -1, -1) }
                }
                withBitmap(-65536, -65536, 0, FreeType.FT_PIXEL_MODE_GRAY, buffer) { bitmap ->
                    val actual = assertThrows(ArithmeticException::class.java) { invoke(face, convert, bitmap, -65536, -65536) }
                    assertEquals("integer overflow", actual.message)
                }
            } finally {
                MemoryUtil.memFree(buffer)
            }
        }
    }

    private fun image(
        face: FreeTypeMinecraftFontFace,
        convert: Method,
        width: Int,
        height: Int,
        pitch: Int,
        fixture: Int,
    ): Pair<DrawImage, IntArray> {
        val stride = abs(pitch)
        val logical = IntArray(width * height) { index -> (index * 73 + index / width * (width / 256) * 19 + fixture * 29) and 0xff }
        val expected = IntArray(logical.size) { index -> channels(logical[index]) }
        val source = ByteArray(stride * height) { index -> ((index * 37 + fixture * 11 + 101) and 0xff).toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val physical = if (0 <= pitch) y else height - y - 1
                source[physical * stride + x] = logical[y * width + x].toByte()
            }
        }
        val scalar =
            IntArray(logical.size) { offset ->
                val y = offset / width
                val x = offset % width
                val physical = if (0 <= pitch) y else height - y - 1
                channels(source[physical * stride + x].toInt() and 0xff)
            }
        assertArrayEquals(expected, scalar)
        val buffer = MemoryUtil.memAlloc(source.size)
        try {
            buffer.put(source).flip()
            val actual = withBitmap(width, height, pitch, FreeType.FT_PIXEL_MODE_GRAY, buffer) { invoke(face, convert, it, width, height) }
            assertEquals(IntSize(width, height), actual.size)
            assertArrayEquals(expected, actual.copyArgb())
            assertArrayEquals(source, ByteArray(source.size) { buffer[it] })
            MemoryUtil.memSet(MemoryUtil.memAddress(buffer), 0, buffer.capacity().toLong())
            assertArrayEquals(expected, actual.copyArgb())
            return actual to expected
        } finally {
            MemoryUtil.memFree(buffer)
        }
    }

    private fun channels(value: Int): Int = (value shl 24) or (value shl 16) or (value shl 8) or value

    private data class Fault(
        val size: IntSize,
        val pitch: Int,
        val mode: Int = FreeType.FT_PIXEL_MODE_GRAY,
    )

    private fun failure(
        face: FreeTypeMinecraftFontFace,
        convert: Method,
        caller: IntSize,
        fault: Fault,
        expected: Pair<Class<out Throwable>, String>,
    ) {
        withBitmap(fault.size.width, fault.size.height, fault.pitch, fault.mode, null) { bitmap ->
            val actual = assertThrows(expected.first) { invoke(face, convert, bitmap, caller.width, caller.height) }
            assertEquals(expected.second, actual.message)
        }
    }

    private fun converter(face: FreeTypeMinecraftFontFace): Method = face.javaClass
        .getDeclaredMethod("pixels", FT_Bitmap::class.java, Int::class.java, Int::class.java)
        .apply { isAccessible = true }

    private fun invoke(
        face: FreeTypeMinecraftFontFace,
        convert: Method,
        bitmap: FT_Bitmap,
        width: Int,
        height: Int,
    ): DrawImage =
        try {
            convert.invoke(face, bitmap, width, height) as DrawImage
        } catch (failure: InvocationTargetException) {
            throw checkNotNull(failure.cause)
        }

    private fun <T> withBitmap(
        width: Int,
        height: Int,
        pitch: Int,
        mode: Int,
        buffer: ByteBuffer?,
        use: (FT_Bitmap) -> T,
    ): T =
        MemoryStack.stackPush().use { stack ->
            val bitmap = FT_Bitmap.calloc(stack)
            val address = bitmap.address()
            MemoryUtil.memPutInt(address + FT_Bitmap.ROWS, height)
            MemoryUtil.memPutInt(address + FT_Bitmap.WIDTH, width)
            MemoryUtil.memPutInt(address + FT_Bitmap.PITCH, pitch)
            MemoryUtil.memPutAddress(address + FT_Bitmap.BUFFER, buffer?.let(MemoryUtil::memAddress) ?: 0L)
            MemoryUtil.memPutByte(address + FT_Bitmap.PIXEL_MODE, mode.toByte())
            use(bitmap)
        }
}
