package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.SampledImageOrientation
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.Locale
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Compares packed natural bounds and the public consumer with the original scalar shift/pixel oracle.
 * Expected columns never call either runtime ink or bounds.
 */
class FontHexGlyphBoundsTest {
    @Test
    fun everyAdmittedColumnAndRowMatchesOriginalScalarBounds() {
        for (width in listOf(8, 16, 24, 32)) {
            for (y in 0 until 16) {
                for (x in 0 until width) {
                    val rows = LongArray(16)
                    rows[y] = 1L shl (width - 1 - x)
                    assertEquals(x..x, FontHexGlyph(width, rows).bounds())
                    assertEquals(scalarBounds(width, rows), FontHexGlyph(width, rows).bounds())
                }
            }
            for (rows in patterns(width)) assertEquals(scalarBounds(width, rows), FontHexGlyph(width, rows).bounds())
        }
    }

    @Test
    fun upperUnusedBitsAndSignedRowsCannotCreateSourceInk() {
        val random = Random(286)
        for (width in listOf(8, 16, 24, 32)) {
            val mask = (1L shl width) - 1L
            assertEquals(0..width, FontHexGlyph(width, LongArray(16) { mask.inv() }).bounds())
            assertEquals(0..0, FontHexGlyph(width, LongArray(16) { mask.inv() or (1L shl (width - 1)) }).bounds())
            for (count in listOf(0, 1, 15, 16, 17)) {
                repeat(128) {
                    val rows = LongArray(count) { random.nextLong() }
                    assertEquals(scalarBounds(width, rows), FontHexGlyph(width, rows).bounds())
                }
            }
        }
    }

    @Test
    fun nonCodecWidthsKeepOriginalShiftWrappingAndColumnCutoff() {
        val random = Random(28_613)
        for (width in listOf(Int.MIN_VALUE, -65, -1, 0, 1, 7, 9, 15, 17, 23, 25, 31, 33, 40, 63, 64, 65)) {
            for (count in listOf(0, 1, 16, 17)) {
                repeat(32) {
                    val rows = LongArray(count) { random.nextLong() }
                    assertEquals(scalarBounds(width, rows), FontHexGlyph(width, rows).bounds())
                }
            }
        }
    }

    @Test
    fun callerMutationCannotChangeAnyOwnedRowOrOldBounds() {
        for (width in listOf(8, 16, 24, 32)) {
            val rows = LongArray(16)
            rows[0] = 1L shl (width - 2)
            rows[15] = 2L
            val glyph = FontHexGlyph(width, rows)
            val old = glyph.bounds()
            rows.fill(-1L)
            assertEquals(1..(width - 2), old)
            assertEquals(old, glyph.bounds())
            assertEquals(false, glyph.ink(0, 0))
            assertEquals(true, glyph.ink(1, 0))
        }
    }

    @Test
    fun completePublicRastersAndRawMetricBitsMatchAcrossCachePolicies() {
        for (width in listOf(8, 16, 24, 32)) {
            val rows = patterns(width)
            for (fractional in listOf(false, true)) {
                val records = rows.mapIndexed { index, bits -> record(0x100 + index, width, bits) }.joinToString("")
                val snapshot = snapshot(records, fractional)
                for (entries in listOf(0, 1, 4096)) {
                    val backend = FontTestBackend()
                    var retained: MinecraftFontGlyph? = null
                    MinecraftFontEngine(snapshot, { backend }, cacheEntries = entries).use { engine ->
                        repeat(2) {
                            rows.forEachIndexed { index, bits ->
                                val glyph = engine.glyph(FontTestResources.defaultFont, 0x100 + index)
                                assertGlyph(width, bits, fractional, glyph)
                                val again = engine.glyph(FontTestResources.defaultFont, 0x100 + index)
                                assertGlyph(width, bits, fractional, again)
                                if (entries == 0) assertNotSame(glyph.image, again.image) else assertSame(glyph, again)
                                if (index == 0) retained = glyph
                            }
                        }
                        assertEquals(true, engine.retainedRasterEntries <= entries)
                        assertEquals(true, engine.retainedRasterBytes <= 16L * 1024 * 1024)
                    }
                    assertEquals(1, backend.closeCalls)
                    assertEquals(0, backend.openCalls)
                    assertEquals(0, backend.decodeCalls)
                    assertGlyph(width, rows.first(), fractional, checkNotNull(retained))
                }
            }
        }
    }

    @Test
    fun orderedNonwinningAndOverlappingOverridesKeepInclusivePadding() {
        val rows = LongArray(16) { 0x80000001L }
        val overrides =
            """[{"from":"Z","to":"z","left":0,"right":31},
                {"from":"A","to":"C","left":-1,"right":33},
                {"from":"A","to":"B","left":0,"right":3}]"""
        val snapshot = snapshot(record('A'.code, 32, rows) + record('C'.code, 32, rows) + record('D'.code, 32, rows), true, overrides)
        MinecraftFontEngine(snapshot, { FontTestBackend() }, cacheEntries = 0).use { engine ->
            assertGlyph(32, rows, true, engine.glyph(FontTestResources.defaultFont, 'A'.code), -1..33)
            assertGlyph(32, rows, true, engine.glyph(FontTestResources.defaultFont, 'C'.code), -1..33)
            assertGlyph(32, rows, true, engine.glyph(FontTestResources.defaultFont, 'D'.code))
        }
    }

    @Test
    fun checkedSelectedWidthFailsBeforeImageAllocationAndEmptyPaddingConsumesItsFullLimit() {
        val rows = LongArray(16)
        val records = record('A'.code, 8, rows)
        val overflow = """[{"from":"A","to":"B","left":-2147483648,"right":2147483647}]"""
        val snapshot = snapshot(records, true, overflow)
        MinecraftFontEngine(snapshot, { FontTestBackend() }, cacheEntries = 0).use { engine ->
            assertThrows(ArithmeticException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
            assertEquals(0, engine.retainedRasterEntries)
        }
        for (bytes in listOf(9L * 16 * 4, 9L * 16 * 4 - 1)) {
            val limited =
                MinecraftFontSnapshot.load(
                    listOf(
                        FontTestResources.source(
                            FontTestResources.font("default", """{"type":"unihex","hex_file":"test:font/limits.zip"}"""),
                            "assets/test/font/limits.zip" to FontTestResources.archive("limits.hex" to records.toByteArray()),
                        ),
                    ),
                    FontTestResources.compatibility,
                    MinecraftFontOptions(),
                    MinecraftFontLoadLimits(maxImageBytes = bytes),
                )
            MinecraftFontEngine(limited, { FontTestBackend() }, cacheEntries = 0).use { engine ->
                if (bytes == 9L * 16 * 4) {
                    assertGlyph(8, rows, true, engine.glyph(FontTestResources.defaultFont, 'A'.code))
                } else {
                    assertThrows(MinecraftFontLoadLimitException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
                    assertEquals(0, engine.retainedRasterEntries)
                }
            }
        }
    }

    @Test
    fun actualForeignThreadAndCleanupFailureCannotInvalidateDetachedUnihexPixels() {
        val rows = LongArray(16) { 0x80000001L }
        val failure = IllegalStateException("Unihex backend cleanup")
        val backend = FontTestBackend(release = { throw failure })
        val engine = MinecraftFontEngine(snapshot(record('A'.code, 32, rows), true), { backend })
        val old = engine.glyph(FontTestResources.defaultFont, 'A'.code)
        try {
            val foreign = FutureTask {
                assertThrows(IllegalStateException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
                assertThrows(IllegalStateException::class.java) { engine.close() }
            }
            val thread = Thread(foreign)
            thread.start()
            foreign.get(10, TimeUnit.SECONDS)
            thread.join(10_000)
            assertSame(old, engine.glyph(FontTestResources.defaultFont, 'A'.code))
        } finally {
            assertSame(failure, assertThrows(IllegalStateException::class.java) { engine.close() })
        }
        assertEquals(1, backend.closeCalls)
        assertEquals(0, engine.retainedRasterEntries)
        assertEquals(0L, engine.retainedRasterBytes)
        assertGlyph(32, rows, true, old)
        assertThrows(IllegalStateException::class.java) { engine.glyph(FontTestResources.defaultFont, 'A'.code) }
    }

    private fun snapshot(
        records: String,
        fractional: Boolean,
        overrides: String = "[]",
    ): MinecraftFontSnapshot =
        FontTestResources.snapshot(
            FontTestResources.font("default", """{"type":"unihex","hex_file":"test:font/bounds.zip","size_overrides":$overrides}"""),
            "assets/test/font/bounds.zip" to FontTestResources.archive("bounds.hex" to records.toByteArray(Charsets.US_ASCII)),
            capabilities = FontTestResources.compatibility.copy(fractionalUnihexAdvance = fractional),
        )

    private fun record(
        scalar: Int,
        width: Int,
        rows: LongArray,
    ): String = "%04X:".format(Locale.ROOT, scalar) + rows.joinToString("") { "%0${width / 4}X".format(Locale.ROOT, it) } + "\n"

    private fun patterns(width: Int): List<LongArray> {
        val mask = (1L shl width) - 1L
        val random = Random(286 + width)
        return listOf(
            LongArray(16),
            LongArray(16) { mask },
            LongArray(16) { 1L shl (width - 1) },
            LongArray(16) { 1L },
            LongArray(16) { if (it % 3 == 0) 1L shl (width / 2) else 0L },
            LongArray(16) { if (it == 0) 1L shl (width - 2) else if (it == 15) 2L else 0L },
            LongArray(16) { if (it % 2 == 0) mask else 0L },
            LongArray(16) { random.nextLong() and random.nextLong() and mask },
        )
    }

    private fun scalarInk(
        width: Int,
        rows: LongArray,
        x: Int,
        y: Int,
    ): Boolean = x in 0..31 && ((rows[y] shl (32 - width)) ushr (31 - x)) and 1L != 0L

    private fun scalarBounds(
        width: Int,
        rows: LongArray,
    ): IntRange {
        var left = width
        var right = -1
        for (y in rows.indices) {
            for (x in 0 until width) {
                if (scalarInk(width, rows, x, y)) {
                    left = minOf(left, x)
                    right = maxOf(right, x)
                }
            }
        }
        return if (right < left) 0..width else left..right
    }

    private fun assertGlyph(
        width: Int,
        rows: LongArray,
        fractional: Boolean,
        actual: MinecraftFontGlyph,
        bounds: IntRange = scalarBounds(width, rows),
    ) {
        val columns = bounds.last - bounds.first + 1
        val expected = IntArray(columns * 16) { index -> if (scalarInk(width, rows, bounds.first + index % columns, index / columns)) -1 else 0 }
        val image = checkNotNull(actual.image)
        assertEquals(IntSize(columns, 16), image.size)
        assertArrayEquals(expected, image.copyArgb())
        val advance = if (fractional) columns / 2.0f + 1.0f else (columns / 2 + 1).toFloat()
        assertEquals(advance.toRawBits(), actual.advance.toRawBits())
        assertEquals(0.0f.toRawBits(), actual.left.toRawBits())
        assertEquals(0.0f.toRawBits(), actual.top.toRawBits())
        assertEquals((columns / 2.0f).toRawBits(), actual.right.toRawBits())
        assertEquals(8.0f.toRawBits(), actual.bottom.toRawBits())
        assertEquals(0.5f.toRawBits(), actual.boldOffset.toRawBits())
        assertEquals(0.5f.toRawBits(), actual.shadowOffset.toRawBits())
        assertEquals(SampledImageOrientation.Normal, actual.orientation)
        assertEquals(MinecraftGlyphChannel.Color, actual.channel)
        assertEquals(null, actual.oversizedRasterSize)
    }
}
