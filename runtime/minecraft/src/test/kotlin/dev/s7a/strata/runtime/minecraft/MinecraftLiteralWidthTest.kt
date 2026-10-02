package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestFace
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Compares metric-only ranges with actual glyph runs, including signed and exceptional native rounding.
 */
internal class MinecraftLiteralWidthTest {
    @Test
    fun scalarRangesMatchPositionedRunsInBothNativeRoundingModes() {
        val text = "A🙂日A"
        listOf(2.25f, -2.75f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE).forEach { advance ->
            listOf(false, true).forEach { saturating ->
                val snapshot =
                    FontTestResources.snapshot(
                        FontTestResources.font("default", """{"type":"ttf","file":"test:input.ttf"}"""),
                        "assets/test/font/input.ttf" to byteArrayOf(1),
                        capabilities = FontTestResources.compatibility.copy(saturatingCeil = saturating),
                    )
                val backend = FontTestBackend(open = { _, _ -> FontTestFace(lookup = { codePoint -> MinecraftFontGlyph(if (codePoint == 'A'.code) advance else 1.25f, 0f, 0f, 0f, 0f, null) }) })
                MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend })).use { renderer ->
                    verifyRanges(renderer)
                    renderer.close()
                    assertThrows(IllegalStateException::class.java) { renderer.literalWidth(text, FontTestResources.defaultFont, 0, text.length) }
                }
            }
        }
    }

    @Test
    fun zeroWidthTextScansEachScalarOnceWithoutRasterCaching() {
        val text = "A".repeat(16_384)
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:input.ttf"}"""),
                "assets/test/font/input.ttf" to byteArrayOf(1),
            )
        var lookups = 0
        val backend =
            FontTestBackend(open = { _, _ ->
                FontTestFace(lookup = {
                    lookups += 1
                    MinecraftFontGlyph(0f, 0f, 0f, 0f, 0f, null)
                })
            })
        MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }, cacheEntries = 0)).use { renderer ->
            assertEquals(text.length, renderer.literalEndWithin(text, FontTestResources.defaultFont, 0, 8))
            assertEquals(text.length, lookups)
            renderer.close()
            assertThrows(IllegalStateException::class.java) { renderer.literalEndWithin(text, FontTestResources.defaultFont, 0, 8) }
        }
    }

    private fun verifyRanges(renderer: MinecraftTextRenderer) {
        val text = "A🙂日A"
        val boundaries = listOf(0, 1, 3, 4, 5)
        boundaries.forEach { start ->
            boundaries.filter { start <= it }.forEach { end ->
                val run = renderer.create(UiText.Literal(text.substring(start, end)), TextStyle.TextField, logicalOrder = true)
                assertEquals(run.nativeWidth, renderer.literalWidth(text, FontTestResources.defaultFont, start, end))
            }
            listOf(0, 1, 7, Int.MAX_VALUE).forEach { maximumWidth ->
                var expected = start
                while (expected < text.length) {
                    val next = expected + Character.charCount(text.codePointAt(expected))
                    if (maximumWidth < renderer.literalWidth(text, FontTestResources.defaultFont, start, next)) break
                    expected = next
                }
                assertEquals(expected, renderer.literalEndWithin(text, FontTestResources.defaultFont, start, maximumWidth))
            }
        }
    }
}
