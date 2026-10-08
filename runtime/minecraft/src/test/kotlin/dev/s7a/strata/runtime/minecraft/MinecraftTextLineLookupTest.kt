package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Compares real logical-boundary lookup with a complete ordered Long-distance reference.
 * Coordinate-read counts execute the same inlined search without adding instrumentation to ordinary input.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextLineLookupTest {
    private val emptyRun = MinecraftTextRenderer.legacy(emptyMap()).use { it.create(UiText.Literal(""), TextStyle.Normal) }

    @Test
    fun exhaustiveNondecreasingCoordinatesPreservePlateausAndMidpointTies() {
        val coordinates = intArrayOf(-4, -1, 0, 2, 4)
        for (length in 1..7) {
            val positions = IntArray(length)

            fun fill(
                index: Int,
                minimum: Int,
            ) {
                if (index == length) {
                    val line = line(positions)
                    for (x in -6..6) assertEquals(reference(positions, x), line.offsetAt(x))
                    return
                }
                for (candidate in minimum until coordinates.size) {
                    positions[index] = coordinates[candidate]
                    fill(index + 1, candidate)
                }
            }
            fill(0, 0)
        }
    }

    @Test
    fun unorderedAndExtremeCoordinatesKeepTheEarliestCompleteScanWinner() {
        val random = Random(180)
        val cases =
            listOf(
                intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE),
                intArrayOf(Int.MAX_VALUE, Int.MIN_VALUE, 0, Int.MIN_VALUE),
                intArrayOf(0, 4, 0, -4, 0, 4),
            ) + List(256) { IntArray(random.nextInt(1, 129)) { random.nextInt() } }
        for (positions in cases) {
            val line = line(positions)
            val probes = intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE, -1, 0, 1) + IntArray(12) { random.nextInt() }
            for (x in probes) assertEquals(reference(positions, x), line.offsetAt(x))
        }
    }

    @Test
    fun longMonotoneLinesHaveLogarithmicReadsAndSignedFallbackVisitsEveryBoundary() {
        for (length in listOf(1, 8, 32_768)) {
            val positions = IntArray(length) { it / 64 - 200 }
            val line = line(positions)
            val bound = 2 * (32 - Integer.numberOfLeadingZeros(length)) + 2
            for (x in listOf(Int.MIN_VALUE, -200, 0, positions.last(), Int.MAX_VALUE)) {
                assertEquals(reference(positions, x), line.offsetAt(x))
                assertTrue(line.boundaryVisits(x) <= bound)
            }
        }
        val unordered = IntArray(32_768) { if (it % 2 == 0) it else -it }
        val fallback = line(unordered)
        for (x in listOf(Int.MIN_VALUE, -16_384, 0, 16_384, Int.MAX_VALUE)) {
            assertEquals(reference(unordered, x), fallback.offsetAt(x))
            assertEquals(unordered.size, fallback.boundaryVisits(x))
        }
    }

    @Test
    fun defensiveOwnershipIncludesTheAdmissionFlagAndSupplementaryOffsets() {
        val positions = intArrayOf(0, 3, 3, 6)
        val offsets = intArrayOf(0, 1, 3, 4)
        val line = line(positions, offsets)
        positions.fill(Int.MIN_VALUE)
        offsets.fill(9)
        assertEquals(1, line.offsetAt(3))
        assertEquals(1, line.offsetAt(4))
        assertEquals(4, line.offsetAt(Int.MAX_VALUE))
        assertEquals(3, line.caretX(2))
        assertEquals(0, line(intArrayOf(0)).offsetAt(Int.MAX_VALUE))
    }

    @Test
    fun actualRoundedFloatPrefixesRetainSignedSaturatedWrappedAndNonFiniteBehaviorWithoutFontReads() {
        val metrics =
            listOf(
                listOf(1.25f, -0.1f, 0f, 0f, 2f),
                listOf(4f, -4f, -2f, 2f, 0f),
                listOf(3e9f, 0f, 1f, -3e9f, 2f),
                listOf(1f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, 1f),
                listOf(Float.NEGATIVE_INFINITY, 0f, Float.POSITIVE_INFINITY, 1f, Float.NaN),
            )
        for (saturating in listOf(false, true)) {
            for (advances in metrics) verifyRounded(advances, saturating)
        }
    }

    private fun verifyRounded(
        advances: List<Float>,
        saturating: Boolean,
    ) {
        val value = "AB🙂CD"
        val scalars = value.codePoints().toArray()
        var calls = 0
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:lookup.ttf"}"""),
                "assets/test/font/lookup.ttf" to byteArrayOf(1),
                capabilities = FontTestResources.compatibility.copy(saturatingCeil = saturating),
            )
        val backend =
            FontTestBackend(open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph {
                        calls += 1
                        return MinecraftFontGlyph(advances.getOrElse(scalars.indexOf(codePoint)) { 1f }, 0f, 0f, 0f, 0f, null)
                    }

                    override fun close() = Unit
                }
            })
        MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { backend })).use { renderer ->
            val layout =
                MinecraftTextLineBreaker.create(
                    MinecraftTextContent.create(UiText.Literal(value), multiline = true),
                    renderer,
                    TextLayout.Multiline(TextWrap.None),
                    Int.MAX_VALUE,
                    TextStyle.Normal,
                    logicalOrder = true,
                )
            val line = layout.lines.single()
            val offsets = IntArray(scalars.size + 1)
            val positions = IntArray(offsets.size)
            var width = 0f
            for (index in scalars.indices) {
                offsets[index + 1] = offsets[index] + Character.charCount(scalars[index])
                width += advances[index]
                positions[index + 1] = renderer.roundedWidth(width)
            }
            val before = calls
            for (index in offsets.indices) assertEquals(positions[index], line.caretX(offsets[index]))
            for (x in listOf(Int.MIN_VALUE, -4, 0, 1, 2, 4, Int.MAX_VALUE)) {
                assertEquals(offsets[reference(positions, x)], line.offsetAt(x))
                line.boundaryVisits(x)
            }
            assertEquals(before, calls)
        }
    }

    private fun line(
        positions: IntArray,
        offsets: IntArray = IntArray(positions.size) { it },
    ): MinecraftTextLine = MinecraftTextLine(offsets.first(), offsets.last(), offsets.last(), emptyRun, offsets, positions)

    private fun reference(
        positions: IntArray,
        x: Int,
    ): Int = positions.indices.minBy { index -> abs(positions[index].toLong() - x.toLong()) }
}
