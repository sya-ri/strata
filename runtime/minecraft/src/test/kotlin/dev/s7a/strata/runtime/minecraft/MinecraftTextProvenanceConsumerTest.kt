@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftTextProvenanceFontFixture.Family
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.math.ceil

/**
 * Independently checks font selection, forward numeric width, public retained Text and detached command lifetime.
 * Visual fixtures intentionally include repeated, decreasing and noncontiguous original source indices.
 */
internal class MinecraftTextProvenanceConsumerTest {
    @Test
    fun displayShapingUsesOriginalScalarFontsAndCompleteForwardLogicalWidth() {
        val text = mixedText()
        val original = MinecraftDenseTextContentReference.create(text)
        val visual =
            listOf(
                MinecraftVisualGlyph('x'.code, 4),
                MinecraftVisualGlyph('y'.code, 1),
                MinecraftVisualGlyph('z'.code, 0),
                MinecraftVisualGlyph('w'.code, 4),
                MinecraftVisualGlyph('v'.code, 3),
            )
        MinecraftTextProvenanceFontFixture().use { fixture ->
            fixture.visual = visual
            val run = fixture.renderer.create(text, TextStyle.ContainerLabel)
            val expectedLogical = listOf(Family.First to 'A'.code, Family.Second to 0x1F642, Family.First to 'B'.code, Family.Second to '日'.code)
            val expectedVisual = visual.map { glyph -> family(original.fontAt(glyph.sourceIndex)) to glyph.codePoint }
            assertEquals(expectedLogical + expectedVisual, fixture.calls)
            assertEquals(16, run.nativeWidth)
            assertEquals(IntSize(16, 9), run.size)
            assertEquals(text, run.text)
            val scope = MinecraftTextRecordingScope()
            run.paint(scope, 0, 0)
            var cursor = 0f
            val expectedCommands =
                expectedVisual.map { (font, _) ->
                    val command = DrawCommand.SampledImage(fixture.image, FloatRect(0.01f, 0.01f, 0.99f, 0.99f), FloatRect(cursor, 0f, cursor + 1f, 1f), ArgbColor(0xff404040.toInt()))
                    cursor += font.advance
                    command
                }
            assertEquals(expectedCommands, scope.commands)
            for (density in 1..3) assertArrayEquals(rasterizeHeadless(expectedCommands, scope.size, density).copyArgb(), rasterizeHeadless(scope.commands, scope.size, density).copyArgb())
            fixture.close()
            assertEquals(0, fixture.liveFaces)
            assertEquals(1, fixture.backendCloses)
            val afterClose = MinecraftTextRecordingScope()
            run.paint(afterClose, 0, 0)
            assertEquals(scope.commands, afterClose.commands)
            assertEquals(original.slice(0, 5), MinecraftTextContent.create(text).slice(0, 5))
        }
    }

    @Test
    fun logicalEditingOrderBypassesAllPreparedDisplayReordering() {
        MinecraftTextProvenanceFontFixture().use { fixture ->
            fixture.visual = listOf(MinecraftVisualGlyph('x'.code, 4), MinecraftVisualGlyph('y'.code, 0))
            val run = fixture.renderer.create(mixedText(), TextStyle.ContainerLabel, logicalOrder = true)
            val logical = listOf(Family.First to 'A'.code, Family.Second to 0x1F642, Family.First to 'B'.code, Family.Second to '日'.code)
            assertEquals(logical + logical, fixture.calls)
            assertEquals(16, run.nativeWidth)
            assertEquals(4, MinecraftTextRecordingScope().also { run.paint(it, 0, 0) }.commands.size)
        }
    }

    @Test
    fun invalidVisualSourceIndicesKeepTheirOriginalValidationAndCleanup() {
        for (offset in listOf(2, 5, Int.MAX_VALUE)) {
            MinecraftTextProvenanceFontFixture().use { fixture ->
                fixture.visual = listOf(MinecraftVisualGlyph('x'.code, offset))
                val failure = assertThrows(IllegalArgumentException::class.java) { fixture.renderer.create(mixedText(), TextStyle.Normal) }
                val expected =
                    if (offset == 2) {
                        "A font offset must identify an original Unicode scalar."
                    } else {
                        "Font backend returned a style index outside the logical text."
                    }
                assertEquals(expected, failure.message)
                fixture.close()
                assertEquals(0, fixture.liveFaces)
                assertEquals(1, fixture.backendCloses)
            }
        }
    }

    @Test
    fun signedZeroExceptionalAndCancellationMetricsKeepExactForwardFloatRounding() {
        val metrics =
            listOf(
                listOf(3f, 5f, -3f, -5f),
                listOf(0f, -0f, 0f, -0f),
                listOf(16_777_216f, 1f, -16_777_216f, 1f),
                listOf(Float.NaN, 3f, -2f, 1f),
                listOf(Float.POSITIVE_INFINITY, 3f, -2f, 1f),
                listOf(Float.NEGATIVE_INFINITY, 3f, -2f, 1f),
                listOf(0.25f, -0.5f, 0.75f, 0.125f),
            )
        val scalars = listOf('A'.code, 0x1F642, 'B'.code, '日'.code)
        for (values in metrics) {
            for (saturating in listOf(false, true)) {
                MinecraftTextProvenanceFontFixture(scalars.zip(values).toMap(), saturating).use { fixture ->
                    val run = fixture.renderer.create(mixedText(), TextStyle.ContainerLabel, logicalOrder = true)
                    var width = 0f
                    for (advance in values) width += advance
                    val truncated = width.toInt()
                    val expected = if (saturating) ceil(width.toDouble()).toInt() else if (truncated.toFloat() < width) truncated + 1 else truncated
                    assertEquals(expected, run.nativeWidth)
                    assertEquals(IntSize(maxOf(0, expected), 9), run.size)
                    assertEquals(scalars + scalars, fixture.calls.map { it.second })
                }
            }
        }
    }

    @Test
    fun fullInvalidTailFailsBeforeAnyGlyphProviderAndHiddenGlyphFailureIsPreserved() {
        MinecraftTextProvenanceFontFixture().use { fixture ->
            val invalid = UiText.concat(UiText.Literal("A".repeat(16384)).withFont(Family.First.id), UiText.Literal("§"))
            assertThrows(IllegalArgumentException::class.java) { fixture.renderer.create(invalid, TextStyle.Normal) }
            assertEquals(emptyList<Pair<Family, Int>>(), fixture.calls)
            fixture.failedScalar = 'Z'.code
            val content = MinecraftTextContent.create(UiText.concat(UiText.Literal("A\n").withFont(Family.First.id), UiText.Literal("Z").withFont(Family.Second.id)), multiline = true)
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    MinecraftTextLineBreaker.create(content, fixture.renderer, TextLayout.Multiline(maxLines = 1), 1, TextStyle.Normal, maxHeight = 0)
                }
            assertEquals("Injected provenance glyph failure.", failure.message)
            assertEquals(listOf(Family.First to 'A'.code, Family.Second to 'Z'.code), fixture.calls)
            fixture.close()
            assertEquals(0, fixture.liveFaces)
            assertEquals(1, fixture.backendCloses)
            assertEquals("A\nZ", content.value)
            assertEquals(UiText.Literal("Z").withFont(Family.Second.id), content.slice(2, 3))
        }
    }

    @Test
    fun publicTextRetainsOldCommandsAndSemanticsAcrossEquivalentFontOnlyAndReflowUpdates() {
        MinecraftTextProvenanceFontFixture().use { fixture ->
            UiTree().use { first ->
                UiTree().use { second ->
                    val initial = UiText.Literal("A🙂B").withFont(Family.First.id)
                    val replacement = UiText.Literal("A🙂B").withFont(Family.Second.id)
                    val policy = TextLayout.Multiline(TextWrap.Character, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    first.update(description(fixture, initial, policy))
                    second.update(description(fixture, initial, policy))
                    val old = frame(first, 32)
                    val oldPixels = rasterizeHeadless(old, IntSize(32, 32)).copyArgb()
                    val independent = frame(second, 32)
                    first.update(description(fixture, initial, policy))
                    assertEquals(old, frame(first, 32))
                    first.update(description(fixture, replacement, policy))
                    val new = frame(first, 32)
                    assertNotEquals(old, new)
                    assertEquals(replacement, first.semantics().single().semantics.label)
                    assertEquals(initial, second.semantics().single().semantics.label)
                    assertEquals(independent, frame(second, 32))
                    assertArrayEquals(oldPixels, rasterizeHeadless(old, IntSize(32, 32)).copyArgb())
                    frame(first, 7)
                    assertEquals(replacement, first.semantics().single().semantics.label)
                    first.close()
                    assertEquals(independent, frame(second, 32))
                    second.close()
                    fixture.close()
                    assertEquals(0, fixture.liveFaces)
                    assertEquals(1, fixture.backendCloses)
                    assertArrayEquals(oldPixels, rasterizeHeadless(old, IntSize(32, 32)).copyArgb())
                    assertEquals(MinecraftDenseTextContentReference.create(initial).slice(0, 4), MinecraftTextContent.create(initial).slice(0, 4))
                }
            }
        }
    }

    private fun mixedText(): UiText =
        UiText.concat(
            UiText.Literal("A").withFont(Family.First.id),
            UiText.Literal("🙂").withFont(Family.Second.id),
            UiText.Literal("B").withFont(Family.First.id),
            UiText.Literal("日").withFont(Family.Second.id),
        )

    private fun family(id: ResourceId): Family = Family.entries.single { it.id == id }

    private fun description(
        fixture: MinecraftTextProvenanceFontFixture,
        text: UiText,
        policy: TextLayout.Multiline,
    ) =
        MinecraftProfileImplementation.createEvaluator(
            fixture.profile,
            { Text(text, policy, TextStyle.ContainerLabel, Modifier.Empty, key = ElementKey(Unit)) },
            textRenderer = fixture.renderer,
        )()

    private fun frame(
        tree: UiTree,
        width: Int,
    ): List<DrawCommand> {
        tree.measure(Constraints.fixed(width, 32))
        tree.layout()
        return tree.paint()
    }
}
