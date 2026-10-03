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
import org.junit.jupiter.api.Assertions.assertTrue
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
                    assertThrows(IllegalStateException::class.java) { renderer.literalPositionAt(text, FontTestResources.defaultFont, 1) }
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
            lookups = 0
            assertEquals(text.length, renderer.literalPositionAt(text, FontTestResources.defaultFont, 1))
            assertEquals(text.length, lookups)
            lookups = 0
            assertEquals(0, renderer.literalPositionAt(text, FontTestResources.defaultFont, 0))
            assertEquals(0, lookups)
            renderer.close()
            assertThrows(IllegalStateException::class.java) { renderer.literalEndWithin(text, FontTestResources.defaultFont, 0, 8) }
        }
    }

    @Test
    fun signedIntegralSuffixMatchesTheOriginalScalarSearchInBothRoundingModes() {
        val text = "A🙂日BA🙂日B"
        val boundaries =
            buildList {
                add(0)
                var position = 0
                while (position < text.length) {
                    position += Character.charCount(text.codePointAt(position))
                    add(position)
                }
            }
        listOf(false, true).forEach { saturating ->
            val snapshot =
                FontTestResources.snapshot(
                    FontTestResources.font("default", """{"type":"ttf","file":"test:input.ttf"}"""),
                    "assets/test/font/input.ttf" to byteArrayOf(1),
                    capabilities = FontTestResources.compatibility.copy(saturatingCeil = saturating),
                )
            val backend =
                FontTestBackend(open = { _, _ ->
                    FontTestFace(lookup = { codePoint ->
                        val advance =
                            when (codePoint) {
                                'A'.code -> 4f
                                0x1F642 -> -3f
                                '日'.code -> -2f
                                else -> 5f
                            }
                        MinecraftFontGlyph(advance, 0f, 0f, 0f, 0f, null)
                    })
                })
            MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend })).use { renderer ->
                verifyIntegralSuffixes(renderer, text, boundaries)
            }
        }
    }

    @Test
    fun longSignedIntegralSuffixRequiresAtMostTwoGlyphScansWithoutCaching() {
        val text = "AB".repeat(8_192)
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:input.ttf"}"""),
                "assets/test/font/input.ttf" to byteArrayOf(1),
            )
        var lookups = 0
        val backend =
            FontTestBackend(open = { _, _ ->
                FontTestFace(lookup = { codePoint ->
                    lookups += 1
                    MinecraftFontGlyph(if (codePoint == 'A'.code) 4f else -3f, 0f, 0f, 0f, 0f, null)
                })
            })
        MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }, cacheEntries = 0)).use { renderer ->
            assertEquals(text.length - 23, renderer.literalIntegralStartWithin(text, FontTestResources.defaultFont, text.length, 8))
            assertTrue(lookups <= 2 * text.length)
            renderer.close()
            assertThrows(IllegalStateException::class.java) { renderer.literalIntegralStartWithin(text, FontTestResources.defaultFont, text.length, 8) }
        }
    }

    @Test
    fun integralSuffixRejectsRangesWhereFloatAdditionCanLoseSpacing() {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:input.ttf"}"""),
                "assets/test/font/input.ttf" to byteArrayOf(1),
            )
        val backend =
            FontTestBackend(open = { _, _ ->
                FontTestFace(lookup = { codePoint ->
                    val advance =
                        when (codePoint) {
                            'A'.code -> 16_777_216f
                            'B'.code -> 1f
                            'C'.code -> -16_777_216f
                            else -> 1.25f
                        }
                    MinecraftFontGlyph(advance, 0f, 0f, 0f, 0f, null)
                })
            })
        MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend })).use { renderer ->
            assertEquals(1, renderer.literalIntegralStartWithin("A", FontTestResources.defaultFont, 1, 0))
            assertEquals(null, renderer.literalIntegralStartWithin("AB", FontTestResources.defaultFont, 2, 0))
            assertEquals(null, renderer.literalIntegralStartWithin("ABC", FontTestResources.defaultFont, 3, 0))
            assertEquals(null, renderer.literalIntegralStartWithin("D", FontTestResources.defaultFont, 1, 0))
            assertEquals(0, renderer.create(UiText.Literal("ABC"), TextStyle.TextField, logicalOrder = true).nativeWidth)
        }
    }

    private fun verifyIntegralSuffixes(
        renderer: MinecraftTextRenderer,
        text: String,
        boundaries: List<Int>,
    ) {
        boundaries.forEach { end ->
            listOf(0, 1, 3, 7, Int.MAX_VALUE).forEach { maximumWidth ->
                var expected = 0
                while (expected < end && maximumWidth < renderer.create(UiText.Literal(text.substring(expected, end)), TextStyle.TextField, logicalOrder = true).nativeWidth) {
                    expected += Character.charCount(text.codePointAt(expected))
                }
                assertEquals(expected, renderer.literalIntegralStartWithin(text, FontTestResources.defaultFont, end, maximumWidth))
            }
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
            val suffix = text.substring(start)
            listOf(-1, 0, 1, 2, 4, 7, Int.MAX_VALUE).forEach { localX ->
                var expected = 0
                var previous = 0L
                while (0 < localX && expected < suffix.length) {
                    val next = expected + Character.charCount(suffix.codePointAt(expected))
                    val width = renderer.create(UiText.Literal(suffix.substring(0, next)), TextStyle.TextField, logicalOrder = true).nativeWidth.toLong()
                    if (localX.toLong() < previous + Math.floorDiv(width - previous + 1L, 2L)) break
                    previous = width
                    expected = next
                }
                assertEquals(expected, renderer.literalPositionAt(suffix, FontTestResources.defaultFont, localX))
            }
        }
    }
}
