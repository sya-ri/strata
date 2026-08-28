package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontCleanup
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontEvidence
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontFixture
import dev.s7a.strata.integration.minecraft.fabric.MinecraftNumericFontGlyph
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Guards numeric evidence fidelity and cleanup without executing a native font operation.
 * These regressions protect raw IEEE payloads, oversized-source identity, scene binding, and failure propagation.
 */
internal class MinecraftNumericFontEvidenceTest {
    @Test
    fun `raw observations retain ieee bits and distinguish oversized sources from empty glyphs`() {
        val glyph =
            MinecraftFontGlyph(
                Float.fromBits(0x7FC12345),
                -0.0f,
                Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY,
                Float.NaN,
                null,
                orientation = SampledImageOrientation.Normal,
                oversizedRasterSize = IntSize(321, 8),
            )
        val observed = MinecraftNumericFontGlyph.from(glyph)
        val entries = observed.entries(MinecraftNumericFontFixture.Case.NegativeSize, 0x41)
        assertEquals("7fc12345,80000000,ff800000,7f800000,7fc00000,3f800000,3f800000", entries["glyph.NegativeSize.41.metrics"])
        assertEquals("321,8", entries["glyph.NegativeSize.41.source"])
        val empty = entries + ("glyph.NegativeSize.41.source" to "0,0")
        assertThrows(IllegalStateException::class.java) { MinecraftNumericFontEvidence.verify(entries, empty) }
    }

    @Test
    fun `normalized reversed glyphs reconstruct native axes without rearranging observations`() {
        val glyph = MinecraftFontGlyph(-3f, -4f, 1f, -1f, 6f, null, orientation = SampledImageOrientation.FlipBoth)
        val observed = MinecraftNumericFontGlyph.from(glyph)
        assertEquals(-1f, observed.left)
        assertEquals(-4f, observed.right)
        assertEquals(6f, observed.top)
        assertEquals(1f, observed.bottom)
    }

    @Test
    fun `numeric scene and exact signed widths cannot be substituted by original scene evidence`() {
        val native = mapOf("numeric.scene.sha256" to "numeric-origin-zero", "scene.sha256" to "unchanged-original", "width.row.2" to Int.MAX_VALUE.toString(), "compatibility.preparedTextBounds" to true.toString())
        assertThrows(IllegalStateException::class.java) { MinecraftNumericFontEvidence.verify(native, native - "numeric.scene.sha256") }
        assertThrows(IllegalStateException::class.java) { MinecraftNumericFontEvidence.verify(native, native + ("numeric.scene.sha256" to "original-origin-eight")) }
        assertThrows(IllegalStateException::class.java) { MinecraftNumericFontEvidence.verify(native, native + ("width.row.2" to "0")) }
        assertThrows(IllegalStateException::class.java) { MinecraftNumericFontEvidence.verify(native, native + ("compatibility.preparedTextBounds" to false.toString())) }
    }

    @Test
    fun `resource cleanup attempts all releases and preserves the original operation failure`() {
        val primary = IllegalStateException("operation")
        val firstClose = IllegalArgumentException("first release")
        val secondClose = IllegalStateException("second release")
        val attempted = mutableListOf<Int>()
        val failure =
            assertThrows(IllegalStateException::class.java) {
                MinecraftNumericFontCleanup.preserving<Unit>({ throw primary }) {
                    MinecraftNumericFontCleanup.closeAll(
                        listOf(
                            failingRelease(attempted, 1, firstClose),
                            AutoCloseable { attempted += 2 },
                            failingRelease(attempted, 3, secondClose),
                        ),
                    )
                }
            }
        assertSame(primary, failure)
        assertEquals(listOf(1, 2, 3), attempted)
        assertSame(firstClose, failure.suppressed.single())
        assertSame(secondClose, firstClose.suppressed.single())
    }

    private fun failingRelease(
        attempted: MutableList<Int>,
        index: Int,
        failure: RuntimeException,
    ): AutoCloseable =
        AutoCloseable {
            attempted += index
            throw failure
        }
}
