package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.util.freetype.FT_Bitmap
import org.lwjgl.util.freetype.FreeType
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Verifies complete detached pixels across native buffer reuse, signed rows and terminal release.
 */
internal class FontRasterOwnershipTest {
    @Test
    fun decodedPngPreservesEveryAlphaBoundaryAndHiddenRgbAfterBufferRelease() {
        val size = IntSize(7, 5)
        val colors = intArrayOf(0x00123456, 0x01020304, 0x7fabcdef, 0x80123456.toInt(), 0xfe765432.toInt(), 0xff102030.toInt())
        val expected = IntArray(size.width * size.height) { colors[it % colors.size] }
        val encoded = png(size, expected)
        val backend = LwjglMinecraftFontBackendFactory.open(compatibility(MinecraftTrueTypeRasterizer.Stb))
        val retained =
            backend.use { owner ->
                val image = owner.decodePng(encoded)
                encoded.fill(0)
                repeat(8) { owner.decodePng(png(size, IntArray(expected.size) { -1 })) }
                image
            }
        assertEquals(size, retained.size)
        assertArrayEquals(expected, retained.copyArgb())
        retained.copyArgb().fill(0)
        assertArrayEquals(expected, retained.copyArgb())
    }

    @Test
    fun everyNativeGlyphRemainsDetachedAfterOtherGlyphsAndBothOwnersClose() {
        val scalars = listOf(0x41, 0x65e5, 0xd55c, 0x1f642)
        rasterizers().forEach { rasterizer ->
            val original = fixture()
            val expected =
                LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                    backend.openTrueType(original, MinecraftTrueTypeSettings(11f, 2f, 0.25f, -0.5f)).use { face ->
                        scalars.map { scalar -> checkNotNull(face.glyph(scalar)) }
                    }
                }
            val retained =
                LwjglMinecraftFontBackendFactory.open(compatibility(rasterizer)).use { backend ->
                    backend.openTrueType(original, MinecraftTrueTypeSettings(11f, 2f, 0.25f, -0.5f)).use { face ->
                        original.fill(0)
                        val glyphs = scalars.map { scalar -> checkNotNull(face.glyph(scalar)) }
                        repeat(8) { scalars.reversed().forEach(face::glyph) }
                        glyphs
                    }
                }
            expected.zip(retained).forEach { (reference, actual) ->
                assertEquals(reference.copy(image = null), actual.copy(image = null))
                val image = checkNotNull(actual.image)
                val pixels = checkNotNull(reference.image).copyArgb()
                assertArrayEquals(pixels, image.copyArgb())
                image.copyArgb().fill(0)
                assertArrayEquals(pixels, image.copyArgb())
            }
        }
    }

    @Test
    fun freeTypeSignedPitchRowsAreDetachedBeforeTheNativeBitmapIsFreed() {
        if (rasterizers().contains(MinecraftTrueTypeRasterizer.FreeType).not()) return
        val expected = intArrayOf(0, 0x01010101, 0x7f7f7f7f, 0x80808080.toInt(), 0xfefefefe.toInt(), -1)
        val images = FreeTypePitch.images(fixture())
        images.forEach { image ->
            assertEquals(IntSize(3, 2), image.size)
            assertArrayEquals(expected, image.copyArgb())
        }
    }

    /**
     * Keeps FreeType symbolic types outside the outer test class loaded by STB-only workers.
     */
    private object FreeTypePitch {
        /**
         * Returns detached images only after padded native rows are zeroed, freed and their face is closed.
         */
        fun images(bytes: ByteArray): List<DrawImage> =
            FreeTypeMinecraftFontFace(bytes, MinecraftTrueTypeSettings()).use { face ->
                // A supplied native bitmap covers negative pitch without relying on one font's native row orientation.
                val convert = face.javaClass.getDeclaredMethod("pixels", FT_Bitmap::class.java, Int::class.java, Int::class.java)
                convert.isAccessible = true
                listOf(5, -5).map { pitch ->
                    val buffer = MemoryUtil.memAlloc(10)
                    try {
                        val rows = if (0 <= pitch) listOf(0, 1, 127, 77, 99, 128, 254, 255, 88, 66) else listOf(128, 254, 255, 88, 66, 0, 1, 127, 77, 99)
                        rows.forEachIndexed { index, value -> buffer.put(index, value.toByte()) }
                        MemoryStack.stackPush().use { stack ->
                            val bitmap = FT_Bitmap.calloc(stack)
                            val address = bitmap.address()
                            MemoryUtil.memPutInt(address + FT_Bitmap.ROWS, 2)
                            MemoryUtil.memPutInt(address + FT_Bitmap.WIDTH, 3)
                            MemoryUtil.memPutInt(address + FT_Bitmap.PITCH, pitch)
                            MemoryUtil.memPutAddress(address + FT_Bitmap.BUFFER, MemoryUtil.memAddress(buffer))
                            MemoryUtil.memPutByte(address + FT_Bitmap.PIXEL_MODE, FreeType.FT_PIXEL_MODE_GRAY.toByte())
                            val image = convert.invoke(face, bitmap, 3, 2) as DrawImage
                            MemoryUtil.memSet(MemoryUtil.memAddress(buffer), 0, buffer.capacity().toLong())
                            image
                        }
                    } finally {
                        MemoryUtil.memFree(buffer)
                    }
                }
            }
    }

    private fun png(
        size: IntSize,
        pixels: IntArray,
    ): ByteArray {
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, size.width, size.height, pixels, 0, size.width)
        return ByteArrayOutputStream().use { output ->
            check(ImageIO.write(image, "PNG", output))
            output.toByteArray()
        }
    }

    private fun compatibility(rasterizer: MinecraftTrueTypeRasterizer): MinecraftFontCompatibility = MinecraftFontCompatibility(rasterizer, 84)

    private fun rasterizers(): List<MinecraftTrueTypeRasterizer> = System.getProperty("strata.fontRasterizer")?.let { listOf(MinecraftTrueTypeRasterizer.valueOf(it)) } ?: MinecraftTrueTypeRasterizer.entries

    private fun fixture(): ByteArray = checkNotNull(javaClass.getResourceAsStream("/fonts/strata-test.ttf")).use { it.readBytes() }
}
