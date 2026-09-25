package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies that active resource-pack ASCII pixels enter the binary common profile without lossy normalization.
 */
internal class FabricMinecraftAsciiMaskTest {
    @Test
    fun copiesOnlyTransparentAndOpaqueWhitePixels() {
        val pixels = IntArray(128 * 128) { 0x00FFFFFF }
        val origin = glyphOrigin(0x21)
        pixels[origin] = -1
        pixels[origin + 7] = -1

        val glyph = extractMinecraftAsciiGlyph(createDrawImage(IntSize(128, 128), pixels), 0x21)

        assertEquals(-1, glyph.argbAt(0, 0))
        assertEquals(-1, glyph.argbAt(7, 0))
        assertEquals(0x00FFFFFF, glyph.argbAt(1, 0))
    }

    @Test
    fun rejectsPartialAlphaAndColoredPixels() {
        listOf(0x80FFFFFF.toInt(), 0xFFFF0000.toInt()).forEach { invalidPixel ->
            val pixels = IntArray(128 * 128) { 0x00FFFFFF }
            pixels[glyphOrigin(0x21)] = invalidPixel
            assertThrows(IllegalArgumentException::class.java) {
                extractMinecraftAsciiGlyph(createDrawImage(IntSize(128, 128), pixels), 0x21)
            }
        }
    }

    @Test
    fun rejectsCodePointsOutsideTheSupportedAsciiRange() {
        val image = createDrawImage(IntSize(128, 128), IntArray(128 * 128) { 0x00FFFFFF })
        listOf(0x20, 0x7F).forEach { codePoint ->
            assertThrows(IllegalArgumentException::class.java) {
                extractMinecraftAsciiGlyph(image, codePoint)
            }
        }
    }

    @Test
    fun rejectsAnAtlasWithTheWrongSizeBeforeReadingPixels() {
        val image = createDrawImage(IntSize(8, 8), IntArray(64) { 0x00FFFFFF })
        assertThrows(IllegalArgumentException::class.java) {
            extractMinecraftAsciiGlyph(image, 0x21)
        }
    }

    private fun glyphOrigin(codePoint: Int): Int = (codePoint / 16 * 8) * 128 + codePoint % 16 * 8
}
