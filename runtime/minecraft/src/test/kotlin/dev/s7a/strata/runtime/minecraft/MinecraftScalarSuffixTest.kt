package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestFace
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Checks attained-extrema suffix searches against independent scalar-order native rounding.
 */
internal class MinecraftScalarSuffixTest {
    @Test
    fun exceptionalSuffixesMatchScalarOrderAcrossBothNativeRoundingModes() {
        val random = Random(27)
        val alphabet = listOf("A", "B", "C", "D", "E", "F", "G", "🙂")
        val palettes =
            listOf(
                listOf(1.25f, -0.1f, 0f, 0.1f, -3.2f, 8f, -0f, Float.MIN_VALUE),
                listOf(Float.MAX_VALUE, -Float.MAX_VALUE, 1f, 0f, 16_777_216f, -1f, Float.MIN_VALUE, 0.1f),
                listOf(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, 2.25f, -2.75f, 0f, 1f, -1f),
            )
        for (palette in palettes) {
            for (saturating in listOf(false, true)) {
                val metrics = alphabet.mapIndexed { index, text -> text.codePointAt(0) to palette[index] }.toMap()
                renderer(metrics, saturating).use { renderer ->
                    repeat(40) {
                        val text = List(40) { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
                        for (maximum in listOf(0, 1, 8, 64)) {
                            var expected = 0
                            while (expected < text.length && maximum < renderer.literalWidth(text, FontTestResources.defaultFont, expected, text.length)) {
                                expected += Character.charCount(text.codePointAt(expected))
                            }
                            assertEquals(expected, renderer.literalScalarStartWithin(text, FontTestResources.defaultFont, text.length, maximum))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun longInexactSignedSpacingAndOverflowRequireOneUncachedGlyphScan() {
        val signed = "AB".repeat(8_192)
        var lookups = 0
        renderer(mapOf('A'.code to 1.25f, 'B'.code to -0.1f), true) { lookups += 1 }.use { renderer ->
            assertEquals(signed.length - 15, renderer.literalScalarStartWithin(signed, FontTestResources.defaultFont, signed.length, 8))
            assertEquals(signed.length, lookups)
        }
        lookups = 0
        val overflow = "AABC".repeat(4_096) + "D"
        renderer(mapOf('A'.code to Float.MAX_VALUE, 'B'.code to 1f, 'C'.code to -Float.MAX_VALUE, 'D'.code to Float.MAX_VALUE), true) { lookups += 1 }.use { renderer ->
            assertEquals(overflow.length - 3, renderer.literalScalarStartWithin(overflow, FontTestResources.defaultFont, overflow.length, 8))
            assertEquals(overflow.length, lookups)
        }
    }

    @Test
    fun aPositiveInfiniteAdvanceSkipsOnlySuffixesWithNoPossibleOpposingOverflow() {
        val text = "AB".repeat(8_192) + "C"
        for (saturating in listOf(false, true)) {
            var lookups = 0
            renderer(mapOf('A'.code to 0.1f, 'B'.code to -0.25f, 'C'.code to Float.POSITIVE_INFINITY), saturating) { lookups += 1 }.use { renderer ->
                assertEquals(if (saturating) text.length else 0, renderer.literalScalarStartWithin(text, FontTestResources.defaultFont, text.length, 8))
                assertEquals(text.length, lookups)
            }
        }
        renderer(mapOf('A'.code to -Float.MAX_VALUE, 'C'.code to Float.POSITIVE_INFINITY), true).use { renderer ->
            assertEquals(0, renderer.literalScalarStartWithin("AAC", FontTestResources.defaultFont, 3, 8))
        }
    }

    @Test
    fun largeFiniteSignedSuffixesKeepLegacyWrappingAndSaturatingRounding() {
        val metrics = mapOf('A'.code to 300_000f, 'B'.code to -0.1f, 'C'.code to 0.05f)
        for (saturating in listOf(false, true)) {
            for (repetitions in listOf(4_096, 8_192)) {
                val text = "ABC".repeat(repetitions)
                var lookups = 0
                renderer(metrics, saturating) { lookups += 1 }.use { renderer ->
                    val expected = if (saturating.not() && repetitions == 8_192) 0 else text.length - 2
                    assertEquals(expected, renderer.literalScalarStartWithin(text, FontTestResources.defaultFont, text.length, 8))
                    assertEquals(text.length, lookups)
                }
            }
        }
    }

    @Test
    fun arbitraryFloatBitsAndIntegerOverflowBoundariesMatchEveryCandidatePrefix() {
        val random = Random(72)
        val alphabet = listOf("A", "B", "C", "D", "🙂")
        for (saturating in listOf(false, true)) {
            repeat(32) {
                val values = List(3) { Float.fromBits(random.nextInt()) } + listOf(Math.nextDown(Int.MAX_VALUE.toFloat()), Math.nextUp(Int.MAX_VALUE.toFloat()))
                val metrics = alphabet.mapIndexed { index, text -> text.codePointAt(0) to values[index] }.toMap()
                renderer(metrics, saturating).use { renderer ->
                    val text = List(32) { alphabet[random.nextInt(alphabet.size)] }.joinToString("")
                    val ends = text.indices.filter { Character.isLowSurrogate(text[it]).not() } + text.length
                    for (end in ends) {
                        for (maximum in listOf(0, 7, Int.MAX_VALUE)) {
                            var expected = 0
                            while (expected < end && maximum < renderer.literalWidth(text, FontTestResources.defaultFont, expected, end)) {
                                expected += Character.charCount(text.codePointAt(expected))
                            }
                            assertEquals(expected, renderer.literalScalarStartWithin(text, FontTestResources.defaultFont, end, maximum))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun longInexactCancellationWithAnOverwideTailKeepsOneGlyphScan() {
        val text = "ABC".repeat(8_192) + "D"
        val metrics = mapOf('A'.code to 1e35f, 'B'.code to -1e35f, 'C'.code to 1e29f, 'D'.code to 1e38f)
        for (saturating in listOf(false, true)) {
            var lookups = 0
            renderer(metrics, saturating) { lookups += 1 }.use { renderer ->
                assertEquals(if (saturating) text.length else 0, renderer.literalScalarStartWithin(text, FontTestResources.defaultFont, text.length, 8))
                assertEquals(text.length, lookups)
            }
        }
    }

    /**
     * Uses immutable uncached metrics so lookup work remains observable independently from glyph caching.
     */
    private fun renderer(
        metrics: Map<Int, Float>,
        saturating: Boolean,
        onLookup: () -> Unit = {},
    ): MinecraftTextRenderer {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:input.ttf"}"""),
                "assets/test/font/input.ttf" to byteArrayOf(1),
                capabilities = FontTestResources.compatibility.copy(saturatingCeil = saturating),
            )
        val backend =
            FontTestBackend(open = { _, _ ->
                FontTestFace(lookup = { codePoint ->
                    onLookup()
                    MinecraftFontGlyph(metrics.getValue(codePoint), 0f, 0f, 0f, 0f, null)
                })
            })
        return MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, MinecraftFontBackendFactory { backend }, cacheEntries = 0))
    }
}
