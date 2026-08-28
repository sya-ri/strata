@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier as ReflectModifier

/**
 * Checks bounded current-run glyph visits against the original clipped paint path without native libraries or a game.
 */
internal class MinecraftTextRunVisibilityTest {
    @Test
    fun longForwardRunsVisitOnlyVisibleCandidatesAtTheStartMiddleAndEnd() {
        val glyph = MinecraftFontGlyph(1.25f, -0.375f, -0.25f, 1.375f, 1.75f, sampledImage(), shadowOffset = 1.5f)
        val viewport = IntRect(4, 4, 44, 20)
        for (interleaved in listOf(false, true)) {
            val run = sampledRun("A".repeat(32_767), mapOf('A'.code to glyph), interleaved = interleaved, shadow = ArgbColor(0x80345678.toInt()))
            for ((scroll, candidates, commands) in listOf(Triple(0, 33, 65), Triple(20_000, 35, 68), Triple(40_920, 33, 65))) {
                val origin = IntOffset(4 - scroll, 6)
                val visits = assertClippedParity(run, origin, viewport)
                assertEquals(1 + candidates * if (interleaved) 1 else 2, visits)
                assertTrue(visits in 1..80, "Unexpected candidate visits at $scroll: $visits")
                assertEquals(commands, capture(run, origin, viewport).commands.size)
            }
        }
        val legacy = legacyRun("A".repeat(32_767)) { 2 }
        for ((scroll, candidates, commands) in listOf(Triple(0, 20, 40), Triple(32_768, 24, 47), Triple(65_500, 21, 41))) {
            val origin = IntOffset(4 - scroll, 6)
            val visits = assertClippedParity(legacy, origin, viewport)
            assertEquals(candidates, visits)
            assertTrue(visits in 1..25)
            assertEquals(commands, capture(legacy, origin, viewport).commands.size)
        }
    }

    @Test
    fun zeroSignedAndNonFiniteAdvancesUseTheConservativeCurrentRunFallback() {
        val normal = MinecraftFontGlyph(2f, 0f, 0f, 1f, 2f, sampledImage())
        val viewport = IntRect(4, 4, 5, 12)
        for (advance in listOf(0f, -0f, -4f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            val glyphs = mapOf('A'.code to normal, 'X'.code to normal.copy(advance = advance), 'B'.code to normal)
            val run = sampledRun("AXB", glyphs)
            assertEquals(4, assertClippedParity(run, IntOffset(4, 5), viewport), "Advance $advance")
        }
        val invalid = normal.copy(left = Float.NaN, right = Float.NaN)
        assertEquals(4, assertClippedParity(sampledRun("AXB", mapOf('A'.code to normal, 'X'.code to invalid, 'B'.code to normal)), IntOffset(4, 5), viewport))
        val spacing = MinecraftFontGlyph(16_777_216f, 0f, 0f, 0f, 0f, null)
        val plateau = sampledRun("P" + "A".repeat(8), mapOf('P'.code to spacing, 'A'.code to normal.copy(advance = 1f)))
        assertEquals(9, assertClippedParity(plateau, IntOffset(4 - 16_777_216, 5), viewport))
        val rejected = sampledRun("A", mapOf('A'.code to normal.copy(orientation = SampledImageOrientation.FlipBoth)))
        assertEquals(1, assertClippedParity(rejected, IntOffset(4, 5), viewport))
    }

    @Test
    fun actualQuadIntersectionsKeepOverhangAndBothShadowOrdersWithoutCropping() {
        val first = MinecraftFontGlyph(2.5f, -12.25f, -2.75f, 3.75f, 4.25f, sampledImage(), shadowOffset = 1.5f)
        val second = first.copy(left = 10.75f, right = 14.25f, top = 3.5f, bottom = 9.25f, shadowOffset = 6.5f, orientation = SampledImageOrientation.FlipBoth)
        for (interleaved in listOf(false, true)) {
            val run = sampledRun("ABABAB", mapOf('A'.code to first, 'B'.code to second), interleaved, ArgbColor(0xA0406080.toInt()))
            for (viewport in listOf(IntRect(4, 4, 12, 14), IntRect(18, 12, 21, 20), IntRect(4, 7, 10, 8))) {
                assertClippedParity(run, IntOffset(7, 8), viewport)
            }
        }
        val shadowOnly = first.copy(left = -4f, right = 0f, top = 0f, bottom = 1f)
        val tint = ArgbColor(0xFF345678.toInt())
        val run = sampledRun("A", mapOf('A'.code to shadowOnly), shadow = tint)
        val viewport = IntRect(0, 1, 1, 3)
        assertClippedParity(run, IntOffset.Zero, viewport)
        val command = capture(run, IntOffset.Zero, viewport).commands.single() as DrawCommand.SampledImage
        assertEquals(tint, command.tint)
        assertEquals(FloatRect(-2.5f, 1.5f, 1.5f, 2.5f), command.destination)
        assertEquals(FloatRect(0.01f, 0.01f, 1.99f, 1.99f), command.source)
    }

    @Test
    fun floatCandidateBoundsApplyTheOriginBeforeBearingsAndShadowOffsets() {
        val spacing = MinecraftFontGlyph(16_777_216f, 0f, 0f, 0f, 0f, null)
        val narrow = MinecraftFontGlyph(2f, 0.25f, 0f, 0.75f, 1f, sampledImage(), shadowOffset = 0.5f)
        val run = sampledRun("PAAA", mapOf('P'.code to spacing, 'A'.code to narrow), shadow = ArgbColor(0x80406080.toInt()))
        val origin = IntOffset(-16_777_216, 4)
        val viewport = IntRect(0, 4, 1, 6)
        assertNull(run.inkBounds())
        assertEquals(MinecraftTextVerticalBounds(4.0, 5.5), run.verticalInkAt(origin.y))
        assertEquals(2, assertClippedParity(run, origin, viewport))
        val commands = capture(run, origin, viewport).commands.filterIsInstance<DrawCommand.SampledImage>()
        assertEquals(listOf(FloatRect(0.75f, 4.5f, 1.25f, 5.5f), FloatRect(0.25f, 4f, 0.75f, 5f)), commands.map { it.destination })
    }

    @Test
    fun actualOriginVerticalBoundsPreserveFloatCancellationAndConservativeOverflow() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val shifted = MinecraftFontGlyph(2f, 0f, -33_554_436f, 1f, -33_554_432f, image)
        val run = sampledRun("A", mapOf('A'.code to shifted), shadow = ArgbColor(-1))
        val origin = IntOffset(0, 33_554_435)
        val viewport = IntRect(0, 4, 2, 5)
        val bounds = checkNotNull(run.verticalInkAt(origin.y))
        assertEquals(MinecraftTextVerticalBounds(0.0, 5.0), bounds)
        assertTrue(bounds.intersects(viewport.top, viewport.bottom))
        assertEquals(-1.0, checkNotNull(run.inkBounds()).bottom + 33_554_431)
        assertClippedParity(run, origin, viewport)
        val visible = capture(run, origin, viewport).commands
        assertEquals(1, visible.size)
        assertEquals(-1, rasterizeHeadless(visible, IntSize(2, 5)).argbAt(1, 4))
        val enormous = shifted.copy(top = 0f, bottom = Float.MAX_VALUE, shadowOffset = Float.MAX_VALUE)
        val overflow = sampledRun("A", mapOf('A'.code to enormous), shadow = ArgbColor(-1))
        assertEquals(MinecraftTextVerticalBounds(0.0, Double.POSITIVE_INFINITY), overflow.verticalInkAt(0))
        val invalid = shifted.copy(top = Float.NaN)
        assertNull(sampledRun("A", mapOf('A'.code to invalid)).verticalInkAt(origin.y))
        assertNull(sampledRun("A", mapOf('A'.code to shifted.copy(image = null))).verticalInkAt(origin.y))
        val legacy = legacyRun("A") { 2 }
        val extreme = checkNotNull(legacy.verticalInkAt(Int.MAX_VALUE))
        assertEquals(MinecraftTextVerticalBounds(Int.MAX_VALUE.toDouble(), Int.MAX_VALUE.toDouble() + 9), extreme)
        assertFalse(extreme.intersects(0, 10))
    }

    @Test
    fun combinedRawVerticalMetricsKeepMonotoneActualOriginsAndExactLegacyEdges() {
        val glyph = MinecraftFontGlyph(2f, 0f, -33_554_436f, 1f, -33_554_432f, sampledImage())
        val sampled = sampledRun("A", mapOf('A'.code to glyph), shadow = ArgbColor(-1))
        val legacy = legacyRun("A") { 2 }
        val builder = MinecraftTextVerticalMetrics.Builder()
        assertNull(builder.build())
        builder.add(sampled.verticalMetrics)
        builder.add(legacy.verticalMetrics)
        builder.add(null)
        val combined = checkNotNull(builder.build())
        assertEquals(-33_554_436.0, combined.minimumTopAt(0L))
        assertEquals(9.0, combined.maximumBottomAt(0L))
        assertEquals(0.0, combined.minimumTopAt(33_554_435L))
        assertEquals(33_554_444.0, combined.maximumBottomAt(33_554_435L))
        val origins = listOf(Int.MIN_VALUE.toLong(), -33_554_435L, -1L, 0L, 1L, 33_554_431L, 33_554_435L, Int.MAX_VALUE.toLong())
        for ((first, second) in origins.zipWithNext()) {
            assertTrue(combined.minimumTopAt(first) <= combined.minimumTopAt(second))
            assertTrue(combined.maximumBottomAt(first) <= combined.maximumBottomAt(second))
        }
    }

    @Test
    fun legacyInkAndCandidateBoundsKeepExactIntegerEdgesAboveFloatPrecision() {
        val large = 16_777_217
        val run = legacyRun("AB") { codePoint -> if (codePoint == 'A'.code) large else 1 }
        assertEquals(large.toDouble() + 9.0, checkNotNull(run.inkBounds()).right)
        assertEquals(1, assertClippedParity(run, IntOffset(4 - large, 4), IntRect(4, 4, 13, 13)))
        val viewport = IntRect(large + 8, 0, large + 9, 9)
        assertEquals(1, assertClippedParity(run, IntOffset.Zero, viewport))
        val shadow = capture(run, IntOffset.Zero, viewport).commands.single() as DrawCommand.BlitImage
        assertEquals(IntRect(large + 1, 1, large + 9, 9), shadow.destination)
        assertEquals(IntRect(0, 0, 8, 8), shadow.source)
    }

    @Test
    fun emptyAndRemoteClipsEmitNothingWithoutRetainingViewportHistory() {
        val glyph = MinecraftFontGlyph(2f, 0f, 0f, 1f, 2f, sampledImage())
        val run = sampledRun("A".repeat(4096), mapOf('A'.code to glyph))
        val fields = run.javaClass.declaredFields.filter { ReflectModifier.isStatic(it.modifiers).not() && it.type.isPrimitive.not() }
        val before =
            fields.associateWith { field ->
                field.isAccessible = true
                field.get(run)
            }
        for (scroll in 0 until 64) {
            val viewport = IntRect(4, 4, 24, 12)
            assertTrue(capture(run, IntOffset(4 - scroll * 128, 5), viewport).visits <= 11)
        }
        val empty = capture(run, IntOffset.Zero, IntRect(0, 0, 0, 1))
        assertEquals(0, empty.visits)
        assertTrue(empty.commands.isEmpty())
        val remote = capture(run, IntOffset.Zero, IntRect(10_000, 0, 10_001, 1))
        assertEquals(0, remote.visits)
        assertTrue(remote.commands.isEmpty())
        before.forEach { (field, value) -> assertSame(value, field.get(run)) }
        val indexField = run.javaClass.getDeclaredField("sampledIndex").apply { isAccessible = true }
        val index = checkNotNull(indexField.get(run))
        assertTrue(
            index.javaClass.declaredFields
                .filter { ReflectModifier.isStatic(it.modifiers).not() }
                .all { it.type.isPrimitive },
        )
        val legacy = legacyRun("A") { 2 }
        assertTrue(capture(legacy, IntOffset(0, Int.MAX_VALUE), IntRect(0, 0, 10, 10)).commands.isEmpty())
    }

    private fun assertClippedParity(
        run: MinecraftTextRun,
        origin: IntOffset,
        viewport: IntRect,
    ): Int {
        val full = capture(run, origin)
        val visible = capture(run, origin, viewport)
        assertEquals(full.commands.filter { intersects(it, viewport) }, visible.commands)
        val fullClipped = listOf(DrawCommand.PushClip(viewport)) + full.commands + DrawCommand.PopClip
        val visibleClipped = listOf(DrawCommand.PushClip(viewport)) + visible.commands + DrawCommand.PopClip
        for (scale in 1..3) {
            assertArrayEquals(rasterizeHeadless(fullClipped, IntSize(64, 32), scale).copyArgb(), rasterizeHeadless(visibleClipped, IntSize(64, 32), scale).copyArgb())
        }
        return visible.visits
    }

    private fun intersects(
        command: DrawCommand,
        viewport: IntRect,
    ): Boolean {
        val bounds =
            when (command) {
                is DrawCommand.BlitImage -> command.destination.let { MinecraftTextInkBounds(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()) }
                is DrawCommand.SampledImage -> command.destination.let { MinecraftTextInkBounds(it.left.toDouble(), it.top.toDouble(), it.right.toDouble(), it.bottom.toDouble()) }
                else -> error("The text run emitted an unexpected command.")
            }
        val horizontal = bounds.left < viewport.right.toDouble() && viewport.left.toDouble() < bounds.right
        val vertical = bounds.top < viewport.bottom.toDouble() && viewport.top.toDouble() < bounds.bottom
        return horizontal && vertical
    }

    private fun capture(
        run: MinecraftTextRun,
        origin: IntOffset,
        viewport: IntRect? = null,
    ): Capture {
        val scope = MinecraftTextRecordingScope()
        val visits =
            if (viewport == null) {
                run.paint(scope, origin.x, origin.y)
                0
            } else {
                run.paintVisible(scope, origin.x, origin.y, viewport)
            }
        return Capture(scope.commands, visits)
    }

    private fun sampledRun(
        text: String,
        glyphs: Map<Int, MinecraftFontGlyph>,
        interleaved: Boolean = true,
        shadow: ArgbColor? = null,
    ): MinecraftTextRun {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("default", """{"type":"ttf","file":"test:visible.ttf"}"""),
                "assets/test/font/visible.ttf" to byteArrayOf(1),
                capabilities = FontTestResources.compatibility.copy(interleavedShadows = interleaved, preparedTextBounds = true),
            )
        val backend =
            FontTestBackend(open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph? = glyphs[codePoint]

                    override fun close() = Unit
                }
            })
        return MinecraftFontEngine(snapshot, { backend }).use { engine ->
            MinecraftTextRun.createFonts(UiText.Literal(text), engine, FontTestResources.defaultFont, ArgbColor(0xCC80A0D0.toInt()), shadow, logicalOrder = true)
        }
    }

    private fun legacyRun(
        text: String,
        advance: (Int) -> Int,
    ): MinecraftTextRun {
        val foreground = createDrawImage(IntSize(8, 8), IntArray(64) { if (it % 3 == 0) -1 else 0xFF50A0E0.toInt() })
        val shadow = createDrawImage(IntSize(8, 8), IntArray(64) { 0x80304050.toInt() })
        return MinecraftTextRun.createNormal(UiText.Literal(text)) { codePoint ->
            MinecraftGlyphSnapshot.create(advance(codePoint), shadow, foreground, shadow, foreground, shadow, foreground, shadow, foreground, foreground)
        }
    }

    private fun sampledImage(): DrawImage = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x4080C0FF, 0xCC4080C0.toInt(), 0x00FFFFFF))

    private data class Capture(
        val commands: List<DrawCommand>,
        val visits: Int,
    )
}
