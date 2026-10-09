package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Compares bounded display layout with the frozen complete engine, including source validation and all visible output.
 * Synthetic CPU resources exercise native Float conversion, original font provenance and ordering without a loaded game.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftBoundedTextLayoutTest {
    @Test
    fun smallLimitsMatchTheCompleteReferenceForEveryWrapAndOverflowPolicy() {
        renderer().use { renderer ->
            for (value in listOf("", "A", "AB C D", "A\r\nB\n", "🙂 A\u3000B")) {
                for (wrap in TextWrap.entries) {
                    for (overflow in TextOverflow.entries) {
                        for (width in listOf(0, 1, 8, Int.MAX_VALUE)) {
                            for (height in listOf(0, 1, 9, 10, Int.MAX_VALUE)) {
                                for (maximum in listOf(1, 2, Int.MAX_VALUE)) {
                                    assertLayout(renderer, UiText.Literal(value), TextLayout.Multiline(wrap, maximum, overflow, 2), width, height)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun longWrappedAndHardBreakTailsKeepExactOverflowProofAndEllipsis() {
        renderer().use { renderer ->
            for (value in listOf("A".repeat(32_767), "AB 🙂CD ".repeat(4681), ("AB\r\n").repeat(4096))) {
                for (wrap in TextWrap.entries) {
                    for (overflow in TextOverflow.entries) {
                        assertLayout(renderer, UiText.Literal(value), TextLayout.Multiline(wrap, 1, overflow), 21, 9)
                        assertLayout(renderer, UiText.Literal(value), TextLayout.Multiline(wrap, 5, overflow, 2), 21, 12)
                    }
                }
            }
        }
    }

    @Test
    fun supplementaryOffsetsAndEveryMandatoryBreakKeepTheirConsumedNextStart() {
        renderer().use { renderer ->
            for (separator in listOf("\r\n", "\n", "\r", "\u000B", "\u000C", "\u0085", "\u2028", "\u2029")) {
                val value = "🙂AB" + separator + "🙂C" + separator
                for (maximum in listOf(1, 2, 3)) {
                    assertLayout(renderer, UiText.Literal(value), TextLayout.Multiline(TextWrap.Character, maximum), 6, Int.MAX_VALUE)
                }
            }
        }
    }

    @Test
    fun mixedFontsAndDisplayOrderingKeepWholeLineOriginalScalarProvenance() {
        renderer(reverse = true).use { renderer ->
            val text = UiText.concat(
                UiText.Literal("אב🙂 ").withFont(ResourceId("test", "compact")),
                UiText.Literal("CD العربية ").withFont(ResourceId("minecraft", "default")),
                UiText.Literal("🙂אב").withFont(ResourceId("test", "compact")),
            )
            for (wrap in TextWrap.entries) {
                for (overflow in TextOverflow.entries) {
                    assertLayout(renderer, text, TextLayout.Multiline(wrap, 2, overflow), 15, 12)
                }
            }
        }
    }

    @Test
    fun signedZeroAndExceptionalPrefixesKeepTheOriginalFirstOverflowRule() {
        for (saturating in listOf(false, true)) {
            for (metrics in listOf<(Int) -> Float>(
                { scalar -> if (scalar == 'B'.code) -4f else 3f },
                { scalar -> if (scalar == 'B'.code) 0f else 3f },
                { scalar -> when (scalar) {
                    'A'.code -> 3e9f
                    'B'.code -> -3e9f
                    'C'.code -> Float.POSITIVE_INFINITY
                    'D'.code -> Float.NEGATIVE_INFINITY
                    else -> Float.NaN
                } },
            )) {
                renderer(compatibility = FontTestResources.compatibility.copy(saturatingCeil = saturating), advance = metrics).use { renderer ->
                    for (wrap in TextWrap.entries) {
                        assertLayout(renderer, UiText.Literal("ABABCDZ\nABABAB"), TextLayout.Multiline(wrap, 1, TextOverflow.Ellipsis), 2, 9)
                    }
                }
            }
        }
    }

    @Test
    fun randomizedSignedLayoutsMatchCompleteRangesAndPixelsWithoutMonotoneAssumptions() {
        val random = Random(135)
        repeat(40) {
            val advances = FloatArray(4) { random.nextInt(-4, 6).toFloat() / 2f }
            val value = buildString { repeat(80) { append("ABCD "[random.nextInt(5)]) } }
            renderer(advance = { scalar -> if (scalar in 'A'.code..'D'.code) advances[scalar - 'A'.code] else 2f }).use { renderer ->
                for (wrap in listOf(TextWrap.Word, TextWrap.Character)) {
                    assertLayout(renderer, UiText.Literal(value), TextLayout.Multiline(wrap, 2, TextOverflow.Ellipsis), 8, 12)
                }
            }
        }
    }

    @Test
    fun hiddenTailGlyphFailureStillPropagatesExactlyEvenAtZeroHeight() {
        val failure = IllegalStateException("hidden source glyph")
        renderer(advance = { scalar -> if (scalar == 'Z'.code) throw failure else 3f }).use { renderer ->
            val content = MinecraftTextContent.create(UiText.Literal("AAAA\nZ"), multiline = true)
            for (height in listOf(0, 1, 9)) {
                assertSame(failure, assertThrows(IllegalStateException::class.java) {
                    MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(maxLines = 1), 3, TextStyle.Normal, maxHeight = height)
                })
                assertSame(failure, assertThrows(IllegalStateException::class.java) {
                    MinecraftCompleteTextLineLayoutReference.create(content, renderer, TextLayout.Multiline(maxLines = 1), 3, TextStyle.Normal, maxHeight = height)
                })
            }
        }
    }

    @Test
    fun hiddenCompatibilityScalarAndFontErrorsRemainAtTheFullMeasurementBoundary() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val glyph = MinecraftGlyphSnapshot.create(3, image, image, image, image, image, image, image, image, image)
        MinecraftTextRenderer.legacy(('A'.code..'Z'.code).associateWith { glyph }).use { renderer ->
            val inputs = listOf(
                UiText.Literal("AAAA\n🙂"),
                UiText.concat(UiText.Literal("AAAA\n"), UiText.Literal("A").withFont(ResourceId("test", "hidden"))),
            )
            for (text in inputs) {
                val content = MinecraftTextContent.create(text, multiline = true)
                val expected = assertThrows(IllegalArgumentException::class.java) {
                    MinecraftCompleteTextLineLayoutReference.create(content, renderer, TextLayout.Multiline(maxLines = 1), 3, TextStyle.Normal, maxHeight = 0)
                }
                val actual = assertThrows(IllegalArgumentException::class.java) {
                    MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(maxLines = 1), 3, TextStyle.Normal, maxHeight = 0)
                }
                assertEquals(expected.message, actual.message)
            }
        }
    }

    @Test
    fun invalidDimensionsAndLineSpacingKeepTheirOriginalFailurePrecedence() {
        renderer(advance = { _ -> error("measurement must not run") }).use { renderer ->
            val content = MinecraftTextContent.create(UiText.Literal("A"), multiline = true)
            assertThrows(IllegalArgumentException::class.java) { MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(), -1, TextStyle.Normal) }
            assertThrows(IllegalArgumentException::class.java) { MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(), 1, TextStyle.Normal, maxHeight = -1) }
            assertThrows(ArithmeticException::class.java) { MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(lineSpacing = Int.MAX_VALUE), 1, TextStyle.Normal) }
            assertThrows(IllegalArgumentException::class.java) { TextLayout.Multiline(maxLines = 0) }
        }
    }

    @Test
    fun actualRangeWorkStopsAtVisiblePlusOneWhileUnboundedAndEditableControlsStayComplete() {
        renderer().use { renderer ->
            val value = "A".repeat(10_000)
            val content = MinecraftTextContent.create(UiText.Literal(value), multiline = true)
            val measure = MinecraftTextLineBreaker::class.java.declaredMethods.single { it.name.startsWith("measure") }.apply { isAccessible = true }
            val widths = measure.invoke(MinecraftTextLineBreaker, content, renderer) as FloatArray
            val ranges = MinecraftTextLineBreaker::class.java.declaredMethods.single { it.name.startsWith("breakLines") }.apply { isAccessible = true }
            for (limit in listOf(1, 2, 6)) {
                val result = ranges.invoke(MinecraftTextLineBreaker, value, widths, renderer, 3, TextWrap.Character, limit) as List<*>
                assertEquals(limit, result.size)
            }
            assertEquals(10_000, (ranges.invoke(MinecraftTextLineBreaker, value, widths, renderer, 3, TextWrap.Character, Int.MAX_VALUE) as List<*>).size)
            val editable = MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(TextWrap.Character), 3, TextStyle.TextField, logicalOrder = true)
            assertEquals(10_000, editable.lines.size)
            assertLayout(renderer, UiText.Literal(value), TextLayout.Multiline(TextWrap.Character), 3, Int.MAX_VALUE)
        }
    }

    @Test
    fun realRetainedTextKeepsFullSemanticsAndOldLayoutAfterReplacementAndDisposal() {
        renderer().use { renderer ->
            val text = UiText.Literal("AB🙂 ".repeat(1000))
            val policy = TextLayout.Multiline(TextWrap.Character, 2, TextOverflow.Ellipsis)
            val original = MinecraftTextLineBreaker.create(MinecraftTextContent.create(text, multiline = true), renderer, policy, 18, TextStyle.Normal, maxHeight = 12)
            val captured = MinecraftTextRecordingScope().also { original.paint(it) }.commands.toList()
            UiTree().use { tree ->
                tree.update(createMinecraftMultilineTextElement(text, renderer, policy, TextStyle.Normal, Modifier.Empty, null))
                assertEquals(IntSize(18, 12), tree.measure(Constraints.fixed(18, 12)))
                tree.layout()
                assertEquals(text, tree.semantics().single().semantics.label)
                tree.paint()
                tree.update(createMinecraftMultilineTextElement(UiText.Literal("C"), renderer, policy, TextStyle.Normal, Modifier.Empty, null))
                tree.measure(Constraints.fixed(9, 9))
                tree.layout()
                assertEquals(UiText.Literal("C"), tree.semantics().single().semantics.label)
            }
            assertEquals(captured, MinecraftTextRecordingScope().also { original.paint(it) }.commands)
            assertEquals(text, original.content.text)
        }
    }

    private fun assertLayout(
        renderer: MinecraftTextRenderer,
        text: UiText,
        policy: TextLayout.Multiline,
        width: Int,
        height: Int,
    ) {
        val content = MinecraftTextContent.create(text, multiline = true)
        val expected = MinecraftCompleteTextLineLayoutReference.create(content, renderer, policy, width, TextStyle.Normal, maxHeight = height)
        val actual = MinecraftTextLineBreaker.create(content, renderer, policy, width, TextStyle.Normal, maxHeight = height)
        assertSame(content, actual.content)
        assertEquals(expected.size, actual.size)
        assertEquals(expected.truncated, actual.truncated)
        assertEquals(expected.lineStep, actual.lineStep)
        assertEquals(expected.inkBounds(), actual.inkBounds())
        assertEquals(expected.lines.size, actual.lines.size)
        for ((before, after) in expected.lines.zip(actual.lines)) {
            assertEquals(listOf(before.start, before.end, before.nextStart), listOf(after.start, after.end, after.nextStart))
            assertEquals(before.run.text, after.run.text)
            assertEquals(before.run.size, after.run.size)
            assertEquals(before.run.nativeWidth, after.run.nativeWidth)
            for (offset in before.start..before.end) assertEquals(before.caretX(offset), after.caretX(offset))
            for (x in listOf(Int.MIN_VALUE, -1, 0, width, Int.MAX_VALUE)) assertEquals(before.offsetAt(x), after.offsetAt(x))
        }
        val before = MinecraftTextRecordingScope().also { expected.paint(it) }.commands
        val after = MinecraftTextRecordingScope().also { actual.paint(it) }.commands
        assertEquals(before, after)
        for (scale in 1..3) {
            assertArrayEquals(rasterizeHeadless(before, IntSize(64, 32), scale).copyArgb(), rasterizeHeadless(after, IntSize(64, 32), scale).copyArgb())
        }
    }

    private fun renderer(
        reverse: Boolean = false,
        compatibility: MinecraftFontCompatibility = FontTestResources.compatibility,
        advance: (Int) -> Float = { _ -> 3f },
    ): MinecraftTextRenderer {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val snapshot = FontTestResources.snapshot(
            FontTestResources.font("minecraft:default", """{"type":"ttf","file":"test:bounded.ttf","size":3}"""),
            FontTestResources.font("test:compact", """{"type":"ttf","file":"test:bounded.ttf","size":2}"""),
            "assets/test/font/bounded.ttf" to byteArrayOf(1),
            capabilities = compatibility,
        )
        val backend = object : MinecraftFontBackend {
            override fun decodePng(bytes: ByteArray): DrawImage = error("No bitmap decoding")
            override fun openTrueType(bytes: ByteArray, settings: MinecraftTrueTypeSettings): MinecraftTrueTypeFace =
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph = MinecraftFontGlyph(advance(codePoint) * settings.size / 3f, 0f, 0f, 1f, 1f, image)
                    override fun close() = Unit
                }
            override fun visualGlyphs(text: String, rightToLeft: Boolean): List<MinecraftVisualGlyph> =
                super.visualGlyphs(text, rightToLeft).let { glyphs -> if (reverse) glyphs.asReversed() else glyphs }
            override fun close() = Unit
        }
        return MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { backend }, cacheEntries = 0))
    }
}
