package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies whole detached cell and Unihex images after source reuse and engine shutdown.
 */
internal class FontRasterOwnershipTest {
    @Test
    fun cellReadsEachPatternedPixelOnceAndRetainsNoSourceStorage() {
        val expected = intArrayOf(0x00123456, 0x01020304, 0x7fabcdef, 0x80123456.toInt(), 0xfe765432.toInt(), 0xff102030.toInt())
        val source = expected.copyOf()
        val visited = mutableListOf<Int>()
        val cell =
            FontBitmapCell.read(IntSize(3, 2)) { x, y ->
                val offset = y * 3 + x
                visited.add(offset)
                source[offset]
            }
        source.fill(0)
        FontBitmapCell.read(IntSize(3, 2)) { _, _ -> -1 }
        assertEquals((0..5).toList(), visited)
        assertEquals(2, cell.rightmost)
        val image = checkNotNull(cell.image)
        assertArrayEquals(expected, image.copyArgb())
        image.copyArgb().fill(0)
        assertArrayEquals(expected, image.copyArgb())
    }

    @Test
    fun uncachedUnihexKeepsCompleteInclusivePaddingAfterReplacementAndClose() {
        val archive = FontTestResources.archive("padded.hex" to "0041:${"80000001".repeat(16)}\n0042:${"FFFFFFFF".repeat(16)}\n".toByteArray())
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"unihex","hex_file":"test:font/padded.zip","size_overrides":[{"from":"A","to":"B","left":-1,"right":32},{"from":"A","to":"B","left":0,"right":3}]}"""),
                "assets/test/font/padded.zip" to archive,
            )
        val retained =
            MinecraftFontEngine(snapshot, { FontTestBackend() }, cacheEntries = 0, cacheBytes = 0).use { engine ->
                val glyph = engine.glyph(FontTestResources.defaultFont, 'A'.code)
                repeat(8) { engine.glyph(FontTestResources.defaultFont, 'B'.code) }
                assertEquals(0, engine.retainedRasterEntries)
                assertEquals(0L, engine.retainedRasterBytes)
                glyph
            }
        val image = checkNotNull(retained.image)
        assertEquals(IntSize(34, 16), image.size)
        assertEquals(18f, retained.advance)
        assertArrayEquals(IntArray(34 * 16) { if (it % 34 in setOf(1, 32)) -1 else 0 }, image.copyArgb())
        image.copyArgb().fill(-1)
        assertArrayEquals(IntArray(34 * 16) { if (it % 34 in setOf(1, 32)) -1 else 0 }, image.copyArgb())
    }
}
