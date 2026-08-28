@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.FontTestBackend
import dev.s7a.strata.runtime.minecraft.font.FontTestResources
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.text.TextWrap
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.withFont
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the common structural line layout without Minecraft classes, a GPU, or native font libraries.
 */
internal class MinecraftMultilineTextTest {
    @Test
    fun emptyNestedWrappersKeepTheirInnermostFontAndEmptySlicesKeepTheirSelection() {
        val inner = ResourceId("test", "inner")
        val outer = ResourceId("test", "outer")
        val empty = UiText.Literal("").withFont(inner).withFont(outer)
        val composed = UiText.concat(empty, UiText.Literal("").withFont(outer))
        for (text in listOf(empty, composed)) {
            val content = MinecraftTextContent.create(text)
            assertEquals(inner, content.fontAt(0))
            assertEquals(inner, MinecraftTextContent.create(content.slice(0, 0)).fontAt(0))
        }
        val content = MinecraftTextContent.create(UiText.Literal("🙂").withFont(inner))
        assertEquals(inner, MinecraftTextContent.create(content.slice(0, 0)).fontAt(0))
        assertEquals(inner, MinecraftTextContent.create(content.slice(2, 2)).fontAt(0))
        assertFalse(MinecraftTextContent.create(UiText.Literal(""), inner).equivalentTo(MinecraftTextContent.create(UiText.Literal(""), outer)))
    }

    @Test
    fun identicalVisibleTextRemeasuresAndRepaintsWhenItsEffectiveFontChanges() {
        val first = ResourceId("test", "first")
        val second = ResourceId("test", "second")
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("test:first", """{"type":"bitmap","file":"test:first.png","ascent":7,"chars":["A."]}"""),
                FontTestResources.font("test:second", """{"type":"bitmap","file":"test:second.png","ascent":7,"chars":["A."]}"""),
                "assets/test/textures/first.png" to byteArrayOf(1),
                "assets/test/textures/second.png" to byteArrayOf(2),
            )
        val sheets = (1..2).associate { value -> value.toByte() to createDrawImage(IntSize(16, 8), IntArray(128) { index -> if (index % 8 < value * 2 - 1) -1 else 0 }) }
        MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { FontTestBackend(decode = { sheets.getValue(it.single()) }) })).use { renderer ->
            for (value in listOf("AA", "\nAA")) {
                val policy = TextLayout.Multiline(TextWrap.None, maxLines = 1, overflow = TextOverflow.Ellipsis)
                UiTree().use { tree ->
                    val initial = UiText.Literal(value).withFont(first)
                    tree.update(createMinecraftMultilineTextElement(initial, renderer, policy, TextStyle.ContainerLabel, Modifier.Empty, null))
                    val initialSize = tree.measure(Constraints())
                    tree.layout()
                    val initialPixels = rasterizeHeadless(tree.paint(), IntSize(30, 20)).copyArgb()
                    val replacement = UiText.Literal(value).withFont(second)
                    tree.update(createMinecraftMultilineTextElement(replacement, renderer, policy, TextStyle.ContainerLabel, Modifier.Empty, null))
                    val replacementSize = tree.measure(Constraints())
                    tree.layout()
                    assertTrue(initialSize.width < replacementSize.width)
                    assertFalse(initialPixels.contentEquals(rasterizeHeadless(tree.paint(), IntSize(30, 20)).copyArgb()))
                    assertEquals(
                        replacement,
                        tree
                            .semantics()
                            .single()
                            .semantics.label,
                    )
                }
            }
        }
    }

    @Test
    fun softWrapAffinityAndNearestLineBoxHitTestingPreserveVisualCaretEdges() {
        metricRenderer().use { renderer ->
            val result = layout(renderer, "ABCA", TextLayout.Multiline(TextWrap.Character, lineSpacing = 11), 5)
            assertEquals(listOf("AB", "CA"), result.lines.map { literal(it.run.text) })
            assertEquals(IntOffset(5, 0), result.caretPosition(2, MinecraftTextCaretAffinity.Upstream))
            assertEquals(IntOffset(0, 20), result.caretPosition(2, MinecraftTextCaretAffinity.Downstream))
            for (y in listOf(-100.0, 0.0, 8.0, 9.0, 14.0, 14.5)) assertEquals(0, result.lineIndexAt(y))
            for (y in listOf(14.5001, 15.0, 19.0, 20.0, 1000.0)) assertEquals(1, result.lineIndexAt(y))
            assertEquals(0, result.offsetAt(0, 14))
            assertEquals(2, result.offsetAt(0, 15))
            val hard = layout(renderer, "AB\nCA", TextLayout.Multiline(lineSpacing = 11), 100)
            assertEquals(0, hard.lineAt(2, MinecraftTextCaretAffinity.Downstream))
            assertEquals(1, hard.lineAt(3, MinecraftTextCaretAffinity.Upstream))
            assertEquals(1, hard.lineAt(3, MinecraftTextCaretAffinity.Downstream))
            assertThrows(IllegalArgumentException::class.java) { result.lineIndexAt(Double.NaN) }
        }
    }

    @Test
    fun wordWrappingPrefersBreakableSpacesButNotNonBreakingSpaces() {
        metricRenderer().use { renderer ->
            for (space in listOf(' ', '\u3000')) {
                val result = layout(renderer, "A${space}BC", TextLayout.Multiline(TextWrap.Word), 8)
                assertEquals(listOf("A$space", "BC"), result.lines.map { literal(it.run.text) })
            }
            for (space in listOf('\u00A0', '\u2007', '\u202F')) {
                val result = layout(renderer, "A${space}BC", TextLayout.Multiline(TextWrap.Word), 8)
                assertEquals(listOf("A${space}B", "C"), result.lines.map { literal(it.run.text) })
            }
        }
    }

    @Test
    fun naturalOverhangSurvivesUnboundedLayoutAndMaximumLineOmission() {
        overhangRenderer().use { renderer ->
            val single = renderer.create(UiText.Literal("A"), TextStyle.ContainerLabel)
            val singleCommands =
                UiTree().use { tree ->
                    tree.update(createMinecraftTextElement(single, Modifier.Empty, null))
                    tree.measure(Constraints())
                    tree.layout()
                    tree.paint()
                }
            val natural = commands(UiText.Literal("A"), renderer, TextLayout.Multiline(), Constraints())
            assertEquals(singleCommands, natural)
            for (overflow in TextOverflow.entries) {
                val truncated = commands(UiText.Literal("A\nA"), renderer, TextLayout.Multiline(maxLines = 1, overflow = overflow), Constraints())
                assertFalse(truncated.any { it is DrawCommand.PushClip })
                for (scale in 1..3) {
                    val image = rasterizeHeadless(truncated, IntSize(30, 20), scale)
                    assertEquals(0xFF404040.toInt(), image.argbAt(scale, 13 * scale))
                }
            }
            assertFalse(commands(UiText.Literal("A"), renderer, TextLayout.Multiline(), Constraints(maxWidth = 100, maxHeight = 100)).any { it is DrawCommand.PushClip })
        }
    }

    @Test
    fun finiteAxesClipAtTheActualViewportAndKeepOtherAxisOverhang() {
        overhangRenderer().use { renderer ->
            for (overflow in TextOverflow.entries) {
                val policy = TextLayout.Multiline(TextWrap.None, maxLines = 1, overflow = overflow)
                val horizontal = commands(UiText.Literal("A"), renderer, policy, Constraints(maxWidth = 4))
                assertEquals(IntRect(0, -3, 4, 14), horizontal.filterIsInstance<DrawCommand.PushClip>().single().bounds)
                val vertical = commands(UiText.Literal("A"), renderer, policy, Constraints(maxHeight = 2))
                assertEquals(IntRect(-2, 0, 7, 2), vertical.filterIsInstance<DrawCommand.PushClip>().single().bounds)
                val exact = commands(UiText.Literal("A"), renderer, policy, Constraints.fixed(4, 9))
                assertEquals(IntRect(0, 0, 4, 9), exact.filterIsInstance<DrawCommand.PushClip>().single().bounds)
                for (scale in 1..3) {
                    val image = rasterizeHeadless(horizontal, IntSize(20, 20), scale)
                    assertEquals(0xFF404040.toInt(), image.argbAt(3 * scale, 13 * scale))
                    assertEquals(0, image.argbAt(4 * scale, 13 * scale))
                    val clipped = rasterizeHeadless(vertical, IntSize(20, 20), scale)
                    assertEquals(0xFF404040.toInt(), clipped.argbAt(scale, scale))
                    assertEquals(0, clipped.argbAt(scale, 2 * scale))
                }
            }
        }
    }

    @Test
    fun currentInkIndexBoundsVisitsForLargeDocumentsAndKeepsExactIntegerLineOffsets() {
        overhangRenderer().use { renderer ->
            val large = layout(renderer, "A\n".repeat(10000), TextLayout.Multiline(), 100)
            assertTrue(large.visibleLines(50000.0, 50036.0).count() <= 7)
            assertEquals(large.lines.lastIndex, large.lineAt(20000))
            assertTrue(large.visibleLines(50000.0, 50000.0).isEmpty())
            val spacing = 33_554_424
            val extreme = layout(renderer, "A\nA", TextLayout.Multiline(lineSpacing = spacing), 100)
            val step = Math.addExact(9, spacing)
            assertEquals(step, extreme.caretPosition(2).y)
            assertEquals(step.toDouble() + 14.0, checkNotNull(extreme.inkBounds()).bottom)
            assertEquals(1..1, extreme.visibleLines(step.toDouble() + 10.0, step.toDouble() + 14.0))
        }
    }

    @Test
    fun immutableDescriptionSupportsConcurrentTreesAndRepeatedSameInstanceUpdates() {
        legacyRenderer().use { renderer ->
            val element = createMinecraftMultilineTextElement(UiText.Literal("AB C"), renderer, TextLayout.Multiline(), TextStyle.ContainerLabel, Modifier.Empty, null)
            UiTree().use { first ->
                UiTree().use { second ->
                    first.update(element)
                    second.update(element)
                    val firstSize = first.measure(Constraints(maxWidth = 4))
                    second.measure(Constraints(maxWidth = 20))
                    first.layout()
                    second.layout()
                    val before = first.paint()
                    val independent = second.paint()
                    first.update(element)
                    assertEquals(firstSize, first.measure(Constraints(maxWidth = 4)))
                    first.layout()
                    assertEquals(before, first.paint())
                    assertEquals(independent, second.paint())
                    first.close()
                    assertEquals(independent, second.paint())
                }
            }
        }
    }

    @Test
    fun allMandatoryBreaksAndCrossWrapperCrLfKeepOriginalSemanticsAndEmptyLines() {
        val text = UiText.concat(UiText.Literal("A\r"), UiText.Literal("\nB\n\r\u000B\u000C\u0085\u2028\u2029").withFont(defaultFont))
        legacyRenderer().use { renderer ->
            val content = MinecraftTextContent.create(text, multiline = true)
            val layout = MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(), 100, TextStyle.Normal)
            assertEquals(listOf("A", "B", "", "", "", "", "", "", ""), layout.lines.map { literal(it.run.text) })
            assertEquals(3, layout.lines[0].nextStart)
            assertEquals(IntSize(2, 81), layout.size)
            assertEquals(text, layout.content.text)
            assertEquals(0, layout.lineAt(1))
            assertEquals(1, layout.lineAt(3))
            assertEquals(layout.lines.lastIndex, layout.lineAt(content.value.length))
            assertFalse(layout.truncated)
        }
    }

    @Test
    fun wordAndCharacterWrappingPreserveAllSpacesAndNeverSplitAScalar() {
        metricRenderer().use { renderer ->
            val examples = listOf(" A", "A ", "A  B", "   ", "日本語🙂A", "AB AB", "A\n B")
            val cases = (0..9).flatMap { width -> examples.map { value -> width to value } }
            for (wrap in listOf(TextWrap.Word, TextWrap.Character)) {
                for ((width, value) in cases) {
                    val content = MinecraftTextContent.create(UiText.Literal(value), multiline = true)
                    val layout = MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(wrap), width, TextStyle.Normal, logicalOrder = true)
                    assertScalarLineRanges(layout, width)
                }
            }
            val cjk = layout(renderer, "日本語🙂", TextLayout.Multiline(TextWrap.Word), 8)
            assertEquals(listOf("日本", "語", "🙂"), cjk.lines.map { literal(it.run.text) })
            val exact = layout(renderer, "A B", TextLayout.Multiline(TextWrap.Word), 4)
            assertEquals(listOf("A ", "B"), exact.lines.map { literal(it.run.text) })
            val narrow = layout(renderer, "A B", TextLayout.Multiline(TextWrap.Word), 3)
            assertEquals(listOf("A", " ", "B"), narrow.lines.map { literal(it.run.text) })
        }
    }

    @Test
    fun caretPositionsAndHitTestingUseOriginalScalarOffsetsOnWrappedLines() {
        metricRenderer().use { renderer ->
            val layout = layout(renderer, "A🙂B\n日", TextLayout.Multiline(TextWrap.Character, lineSpacing = 2), 7)
            assertEquals(listOf("A🙂", "B", "日"), layout.lines.map { literal(it.run.text) })
            assertEquals(IntOffset(2, 0), layout.caretPosition(1))
            assertEquals(IntOffset(0, 11), layout.caretPosition(3))
            assertEquals(IntOffset(3, 11), layout.caretPosition(4))
            assertEquals(IntOffset(0, 22), layout.caretPosition(5))
            assertEquals(1, layout.offsetAt(2, 0))
            assertEquals(3, layout.offsetAt(100, 0))
            assertEquals(4, layout.offsetAt(100, 12))
            assertEquals(5, layout.offsetAt(-3, 24))
            assertEquals(6, layout.offsetAt(100, 1000))
            assertEquals(1, layout.content.scalarBoundary(2))
            assertThrows(IllegalArgumentException::class.java) { layout.content.slice(0, 2) }
            assertThrows(IllegalArgumentException::class.java) { layout.content.fontAt(2) }
        }
    }

    @Test
    fun nestedFontsSurviveHardBreaksWrappingAndScalarSlicing() {
        val inner = ResourceId("test", "inner")
        val outer = ResourceId("test", "outer")
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("test:outer", """{"type":"space","advances":{"A":2,"B":2,"🙂":2,".":1}}"""),
                FontTestResources.font("test:inner", """{"type":"space","advances":{"A":3,"B":3,"🙂":3,".":1}}"""),
            )
        MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { FontTestBackend() })).use { renderer ->
            val text = UiText.concat(UiText.Literal("A"), UiText.Literal("🙂\r\nB").withFont(inner), UiText.Literal("A")).withFont(outer)
            val content = MinecraftTextContent.create(text, multiline = true)
            val result = MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(TextWrap.Character), 5, TextStyle.Normal)
            assertEquals(listOf(5, 5), result.lines.map { it.run.nativeWidth })
            assertEquals(listOf(outer, inner, inner, outer), listOf(0, 1, 5, 6).map(content::fontAt))
            assertEquals(inner, MinecraftTextContent.create(result.lines[1].run.text).fontAt(0))
            assertEquals(text, result.content.text)
        }
    }

    @Test
    fun ellipsisHonorsItsFontWidthAndFallsBackToClipWhenTheMarkerCannotFit() {
        for (dotAdvance in listOf(-1, 0, 1, 4)) {
            metricRenderer(dotAdvance).use { renderer ->
                val policy = TextLayout.Multiline(TextWrap.None, maxLines = 1, overflow = TextOverflow.Ellipsis)
                for (width in 0..10) {
                    val result = layout(renderer, "A🙂B\n日", policy, width)
                    val value =
                        literal(
                            result.lines
                                .single()
                                .run.text,
                        )
                    val markerFits = dotAdvance * 3 <= width
                    assertEquals(markerFits, value.endsWith("..."))
                    if (markerFits) {
                        assertTrue(
                            result.lines
                                .single()
                                .run.nativeWidth <= width,
                        )
                    } else {
                        assertEquals("A🙂B", value)
                    }
                    assertTrue(result.truncated)
                    assertEquals(UiText.Literal("A🙂B\n日"), result.content.text)
                }
            }
        }
        legacyRenderer().use { renderer ->
            val clipped = layout(renderer, "ABC\nA", TextLayout.Multiline(maxLines = 1), 100)
            assertEquals(
                "ABC",
                literal(
                    clipped.lines
                        .single()
                        .run.text,
                ),
            )
            val ellipsized = layout(renderer, "ABC\nA", TextLayout.Multiline(maxLines = 1, overflow = TextOverflow.Ellipsis), 8)
            assertEquals(
                "A...",
                literal(
                    ellipsized.lines
                        .single()
                        .run.text,
                ),
            )
        }
    }

    @Test
    fun emptyAndPartiallyVisibleLayoutsUseStructuralBoundsWithoutLosingSemantics() {
        legacyRenderer().use { renderer ->
            val content = MinecraftTextContent.create(UiText.Literal("A\nB"), multiline = true)
            val result = MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(lineSpacing = 3), 10, TextStyle.Normal, maxHeight = 2)
            assertEquals(1, result.lines.size)
            assertEquals(IntSize(2, 9), result.size)
            val hidden = MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(), 0, TextStyle.Normal, maxHeight = 0)
            assertTrue(hidden.lines.isEmpty())
            assertEquals(content.text, hidden.content.text)
            assertEquals(IntOffset.Zero, hidden.caretPosition(1))
            assertEquals(0, hidden.offsetAt(1, 1))
            val empty = layout(renderer, "", TextLayout.Multiline(), 100)
            assertEquals(IntSize(0, 9), empty.size)
            assertEquals(1, empty.lines.size)
            for (height in listOf(0, 1, 2, 8, 9, 11, 12, 13)) {
                val bounded = MinecraftTextLineBreaker.create(content, renderer, TextLayout.Multiline(lineSpacing = 3), 10, TextStyle.Normal, maxHeight = height)
                assertEquals(
                    if (height == 0) {
                        0
                    } else if (height <= 12) {
                        1
                    } else {
                        2
                    },
                    bounded.lines.size,
                )
                assertEquals(content.text, bounded.content.text)
            }
        }
    }

    @Test
    fun compatibilityAndResourceProfilesShareWrappedPixelsAtEveryGuiScale() {
        legacyRenderer().use { legacy ->
            bitmapRenderer().use { resource ->
                val text = UiText.Literal("AB C\nA.B")
                for (width in listOf(1, 2, 4, 8, 20)) {
                    val policy = TextLayout.Multiline(TextWrap.Character, lineSpacing = 1)
                    val constraints = Constraints(maxWidth = width, maxHeight = 50)
                    val legacyCommands = commands(text, legacy, policy, constraints)
                    val resourceCommands = commands(text, resource, policy, constraints)
                    for (scale in 1..3) {
                        assertArrayEquals(
                            rasterizeHeadless(legacyCommands, IntSize(30, 60), scale).copyArgb(),
                            rasterizeHeadless(resourceCommands, IntSize(30, 60), scale).copyArgb(),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun clipCommandsConstrainOversizedGlyphsAndKeepCompleteSemanticText() {
        legacyRenderer().use { renderer ->
            UiTree().use { tree ->
                val text = UiText.Literal("ABC\nB")
                val policy = TextLayout.Multiline(TextWrap.None, overflow = TextOverflow.Clip)
                tree.update(createMinecraftMultilineTextElement(text, renderer, policy, TextStyle.ContainerLabel, Modifier.Empty, null))
                assertEquals(IntSize(1, 2), tree.measure(Constraints.fixed(1, 2)))
                tree.layout()
                val commands = tree.paint()
                assertEquals(IntRect(0, 0, 1, 2), commands.filterIsInstance<DrawCommand.PushClip>().single().bounds)
                assertEquals(1, commands.count { it is DrawCommand.PopClip })
                val semantic = tree.semantics().single { it.semantics.role == SemanticsRole.Text }
                assertEquals(text, semantic.semantics.label)
                assertNarrowClipPixels(commands)
                val previous = commands
                tree.update(createMinecraftMultilineTextElement(text, renderer, policy, TextStyle.ContainerLabel, Modifier.Empty, null))
                tree.measure(Constraints.fixed(1, 2))
                tree.layout()
                assertEquals(previous, tree.paint())
                tree.update(createMinecraftMultilineTextElement(UiText.Literal("B"), renderer, policy, TextStyle.ContainerLabel, Modifier.Empty, null))
                tree.measure(Constraints(maxWidth = 10, maxHeight = 20))
                tree.layout()
                assertEquals(
                    UiText.Literal("B"),
                    tree
                        .semantics()
                        .single()
                        .semantics.label,
                )
            }
        }
    }

    @Test
    fun publicMultilineOverloadUsesParentConstraintsWhileExistingTextStaysSingleLine() {
        val definition = ScreenDefinition(UiText.Literal("Multiline")) { Text("A\nB", TextLayout.Multiline(lineSpacing = 2)) }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            val frame = host.frame(IntSize(20, 30))
            assertTrue(frame.drawCommands.any { it is DrawCommand.PushClip })
        }
        for (separator in listOf('\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')) {
            createMinecraftUiHost(ScreenDefinition(UiText.Literal("Single")) { Text("A${separator}B") }, MinecraftProfileFixture.create()).use { host ->
                assertThrows(IllegalArgumentException::class.java) { host.attach() }
            }
        }
    }

    private fun layout(
        renderer: MinecraftTextRenderer,
        value: String,
        policy: TextLayout.Multiline,
        width: Int,
    ): MinecraftTextLayout = MinecraftTextLineBreaker.create(MinecraftTextContent.create(UiText.Literal(value), multiline = true), renderer, policy, width, TextStyle.ContainerLabel, logicalOrder = true)

    private fun commands(
        text: UiText,
        renderer: MinecraftTextRenderer,
        policy: TextLayout.Multiline,
        constraints: Constraints,
    ): List<DrawCommand> =
        UiTree().use { tree ->
            tree.update(createMinecraftMultilineTextElement(text, renderer, policy, TextStyle.ContainerLabel, Modifier.Empty, null))
            tree.measure(constraints)
            tree.layout()
            tree.paint()
        }

    private fun literal(text: UiText): String = MinecraftTextContent.create(text).value

    private fun assertScalarLineRanges(
        layout: MinecraftTextLayout,
        width: Int,
    ) {
        val content = layout.content
        val value = content.value
        assertEquals(value.replace("\n", ""), layout.lines.joinToString("") { literal(it.run.text) })
        for (line in layout.lines) {
            assertEquals(line.start, content.scalarBoundary(line.start))
            assertEquals(line.end, content.scalarBoundary(line.end))
            if (1 < value.codePointCount(line.start, line.end)) assertTrue(line.run.nativeWidth <= width)
            if (line.end < line.nextStart) assertEquals('\n', value[line.end])
        }
    }

    private fun assertNarrowClipPixels(commands: List<DrawCommand>) {
        for (scale in 1..3) {
            val image = rasterizeHeadless(commands, IntSize(8, 12), scale)
            for (y in 0 until image.size.height) {
                for (x in 0 until image.size.width) {
                    if (scale <= x || 2 * scale <= y) assertEquals(0, image.argbAt(x, y))
                }
            }
        }
    }

    private fun legacyRenderer(): MinecraftTextRenderer {
        val image = mask(0xFF404040.toInt())
        val glyph = MinecraftGlyphSnapshot.create(2, image, image, image, image, image, image, image, image, image)
        return MinecraftTextRenderer.legacy((0x21..0x7E).associateWith { glyph })
    }

    private fun metricRenderer(dotAdvance: Int = 1): MinecraftTextRenderer {
        val snapshot = FontTestResources.snapshot(FontTestResources.font("minecraft:default", """{"type":"space","advances":{"A":2,"B":3,"C":2,"日":4,"本":4,"語":4,"🙂":5,".":$dotAdvance," ":2,"\u00A0":2,"\u2007":2,"\u202F":2,"\u3000":2}}"""))
        return MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { FontTestBackend() }))
    }

    private fun bitmapRenderer(): MinecraftTextRenderer {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("minecraft:default", """{"type":"bitmap","file":"test:glyphs.png","ascent":7,"chars":["ABC."]},{"type":"space","advances":{" ":4}}"""),
                "assets/test/textures/glyphs.png" to byteArrayOf(1),
            )
        val sheet = createDrawImage(IntSize(32, 8), IntArray(256) { index -> if (index % 8 == 0) -1 else 0 })
        return MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { FontTestBackend(decode = { sheet }) }))
    }

    private fun mask(color: Int): DrawImage = createDrawImage(IntSize(8, 8), IntArray(64) { index -> if (index % 8 == 0) color else 0 })

    private fun overhangRenderer(): MinecraftTextRenderer {
        val snapshot =
            FontTestResources.snapshot(
                FontTestResources.font("minecraft:default", """{"type":"ttf","file":"test:overhang.ttf","size":11,"oversample":1,"shift":[0,0]}"""),
                "assets/test/font/overhang.ttf" to byteArrayOf(1),
            )
        val image = createDrawImage(IntSize(9, 17), IntArray(153) { -1 })
        val backend =
            FontTestBackend(open = { _, _ ->
                object : MinecraftTrueTypeFace {
                    override fun glyph(codePoint: Int): MinecraftFontGlyph = MinecraftFontGlyph(4f, -2f, -3f, 7f, 14f, image)

                    override fun close() = Unit
                }
            })
        return MinecraftTextRenderer.fonts(MinecraftFontEngine(snapshot, { backend }))
    }

    private companion object {
        val defaultFont = ResourceId("minecraft", "default")
    }
}
