package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftTextFieldFixture.fieldSize
import dev.s7a.strata.runtime.minecraft.MinecraftTextFieldFixture.host
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackendFactory
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.MinecraftVisualGlyph
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies Unicode scalar editing, resource-font geometry, composition, and editable-focus ownership.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftUnicodeTextFieldTest {
    @Test
    fun stateValidatesUnicodeLengthThreadAndDistinctWrites() {
        assertThrows(IllegalArgumentException::class.java) { TextFieldState("", 0) }
        listOf("\n", "\u0000", "\u007F", "\u00A7", "\uD83D", "\uDE42", "\uD83DA").forEach { unsupported ->
            assertThrows(IllegalArgumentException::class.java) { TextFieldState(unsupported) }
        }
        assertThrows(IllegalArgumentException::class.java) { TextFieldState("abc", 2) }
        assertEquals("日本한국🙂", TextFieldState("日本한국🙂", 6).value)
        assertThrows(IllegalArgumentException::class.java) { TextFieldState("日本한국🙂", 5) }

        val state = TextFieldState("abc", 3)
        state.value = "xyz"
        assertEquals("xyz", state.value)
        assertThrows(IllegalArgumentException::class.java) { state.value = "long" }
        assertThrows(IllegalArgumentException::class.java) { state.value = "\uD83D" }
        assertEquals("xyz", state.value)

        val wrongThread = FutureTask<Throwable?> { runCatching { state.value }.exceptionOrNull() }
        val thread = Thread(wrongThread)
        thread.start()
        try {
            assertTrue(wrongThread.get(5, TimeUnit.SECONDS) is IllegalStateException)
        } finally {
            thread.join(5_000)
        }
    }

    @Test
    fun scalarEditingPreservesPairsAndMovesThroughCombiningMarksIndependently() {
        val state = TextFieldState("", maxLength = 16)
        val host = host(state, Modifier.Empty.initialFocus())
        try {
            host.attach()
            host.frame(fieldSize)
            listOf('日'.code, '한'.code, 0x1F642, 0x0301).forEach { codePoint ->
                assertSame(InputResult.Consumed, host.dispatchTextInput(TextInputEvent.Character(codePoint)))
            }
            assertEquals("日한🙂\u0301", state.value)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Left, 0))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Left, 0))
            host.dispatchTextInput(TextInputEvent.Character('A'.code))
            assertEquals("日한A🙂\u0301", state.value)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Delete, 0))
            assertEquals("日한A\u0301", state.value)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            assertEquals("日한\u0301", state.value)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.End, 0))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            assertEquals("日한", state.value)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            host.dispatchTextInput(TextInputEvent.Character('本'.code))
            assertEquals("日本", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun supplementaryInputFitsAtomicallyWithinTheUtf16Maximum() {
        val state = TextFieldState("", maxLength = 3)
        val host = host(state, Modifier.Empty.initialFocus())
        try {
            host.attach()
            host.frame(fieldSize)
            host.dispatchTextInput(TextInputEvent.Character('A'.code))
            host.dispatchTextInput(TextInputEvent.Character(0x1F642))
            assertEquals("A🙂", state.value)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            host.dispatchTextInput(TextInputEvent.Character('B'.code))
            assertSame(InputResult.Consumed, host.dispatchTextInput(TextInputEvent.Character(0x1F642)))
            assertEquals("AB", state.value)
            host.dispatchTextInput(TextInputEvent.Character('C'.code))
            assertEquals("ABC", state.value)
            listOf(0, 10, 31, 127, 167).forEach { codePoint ->
                assertSame(InputResult.Ignored, host.dispatchTextInput(TextInputEvent.Character(codePoint)))
            }
            assertEquals("ABC", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun externalWritesKeepTheRetainedCursorOnAScalarBoundary() {
        val state = TextFieldState("A")
        val host = host(state, Modifier.Empty.initialFocus())
        try {
            host.attach()
            host.frame(fieldSize)
            state.value = "🙂Z"
            host.dispatchTextInput(TextInputEvent.Character('B'.code))
            assertEquals("B🙂Z", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun inlinePreeditUsesTheSuppliedCaretAndFocusedBlockWithoutCommittingText() {
        val state = TextFieldState("A")
        val host = host(state, Modifier.Empty.initialFocus())
        try {
            host.attach()
            host.frame(fieldSize)
            host.dispatchTextInput(TextInputEvent.Preedit("AA", 0, listOf("A", "A"), 1))
            val first = host.frame(fieldSize)
            assertEquals("A", state.value)
            assertEquals(
                UiText.Literal("A"),
                first.semantics
                    .single()
                    .semantics.label,
            )
            assertEquals(
                listOf(IntRect(8, 15, 10, 16), IntRect(6, 5, 7, 16)),
                first.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { command -> command.bounds },
            )
            host.dispatchTextInput(TextInputEvent.Preedit("AA", 1, listOf("A", "A"), 0))
            assertEquals(
                listOf(IntRect(6, 15, 8, 16), IntRect(8, 5, 9, 16)),
                host
                    .frame(fieldSize)
                    .drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .map { command -> command.bounds },
            )
            host.dispatchTextInput(TextInputEvent.Preedit("", 0, emptyList(), -1))
            assertTrue(host.frame(fieldSize).drawCommands.none { command -> command is DrawCommand.FillRectangle })
            assertEquals("A", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun unicodePreeditRejectsMalformedOffsetsAndCommitsOnlyCharacterEvents() {
        val state = TextFieldState("", maxLength = 8)
        val host = host(state, Modifier.Empty.initialFocus())
        try {
            host.attach()
            host.frame(fieldSize)
            assertSame(InputResult.Ignored, host.dispatchTextInput(TextInputEvent.Preedit("🙂", 1, listOf("🙂"), 0)))
            assertSame(InputResult.Ignored, host.dispatchTextInput(TextInputEvent.Preedit("\uD83D", 1, emptyList(), -1)))
            assertSame(InputResult.Consumed, host.dispatchTextInput(TextInputEvent.Preedit("日本🙂", 2, listOf("日本", "🙂"), 1)))
            assertEquals("", state.value)
            host.dispatchTextInput(TextInputEvent.Character('한'.code))
            assertEquals("한", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun editableFocusIntervalsFollowFieldsAndAttachmentWithoutCommittingPreedit() {
        val firstState = TextFieldState("A")
        val secondState = TextFieldState("B")
        val viewport = IntSize(fieldSize.width, fieldSize.height * 2)
        val host =
            createMinecraftUiHost(
                ScreenDefinition("editable focus") {
                    Column {
                        TextField(firstState, modifier = Modifier.Empty.initialFocus())
                        TextField(secondState)
                    }
                },
                MinecraftProfileFixture.create(),
            )
        try {
            assertNull(host.textInputFocus)
            host.attach()
            assertNull(host.textInputFocus)
            host.frame(viewport)
            val first = checkNotNull(host.textInputFocus)
            val frame = host.frame(viewport)
            assertSame(first, host.textInputFocus)
            assertSame(frame, host.frame(viewport))
            assertSame(first, host.textInputFocus)
            host.dispatchTextInput(TextInputEvent.Preedit("C", 1, listOf("C"), 0))
            assertSame(first, host.textInputFocus)
            host.dispatchPointer(PointerEvent.Press(IntOffset(4, fieldSize.height + 4), PointerButton.Primary))
            val second = checkNotNull(host.textInputFocus)
            assertNotSame(first, second)
            assertEquals("A", firstState.value)
            assertEquals("B", secondState.value)
            host.dispatchTextInput(TextInputEvent.Character('C'.code))
            assertEquals("CB", secondState.value)
            assertSame(second, host.textInputFocus)
            host.dispatchPointer(PointerEvent.Press(IntOffset(-1, -1), PointerButton.Primary))
            assertNull(host.textInputFocus)
            host.dispatchPointer(PointerEvent.Press(IntOffset(4, fieldSize.height + 4), PointerButton.Primary))
            assertNotSame(second, checkNotNull(host.textInputFocus))
            host.detach()
            assertNull(host.textInputFocus)
            host.attach()
            assertNull(host.textInputFocus)
            host.frame(viewport)
            assertNotSame(first, checkNotNull(host.textInputFocus))
            assertEquals("A", firstState.value)
            assertEquals("CB", secondState.value)
        } finally {
            host.close()
        }
        assertThrows(IllegalStateException::class.java) { host.textInputFocus }
    }

    @Test
    fun reenabledRetainedFieldRestoresItsCursorWithoutRestoringOldPreedit() {
        val state = TextFieldState("A", maxLength = 1)
        val profile = MinecraftProfileFixture.create()
        val renderer = MinecraftProfileImplementation.createTextRenderer(profile, null)
        val tree = UiTree()

        fun update(enabled: Boolean): List<DrawCommand> {
            val content =
                MinecraftProfileImplementation.createEvaluator(
                    profile,
                    { TextField(state, enabled = enabled, modifier = Modifier.Empty.initialFocus()) },
                    textRenderer = renderer,
                )
            tree.update(content())
            tree.measure(Constraints.fixed(fieldSize.width, fieldSize.height))
            tree.layout()
            return tree.paint()
        }

        try {
            assertTrue(update(false).none { it is DrawCommand.FillRectangle })
            assertSame(InputResult.Ignored, tree.dispatchTextInput(TextInputEvent.Character('B'.code)))
            val caret = listOf(IntRect(6, 5, 7, 16))
            assertEquals(caret, update(true).filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds })
            tree.dispatchTextInput(TextInputEvent.Preedit("B", 1, listOf("B"), 0))
            assertEquals(2, tree.paint().filterIsInstance<DrawCommand.FillRectangle>().size)
            assertTrue(update(false).none { it is DrawCommand.FillRectangle })
            assertSame(InputResult.Ignored, tree.dispatchTextInput(TextInputEvent.Preedit("C", 1, listOf("C"), 0)))
            assertEquals(caret, update(true).filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds })
            assertEquals("A", state.value)
            tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            tree.dispatchTextInput(TextInputEvent.Character('B'.code))
            assertEquals("B", state.value)
        } finally {
            tree.close()
            renderer.close()
        }
    }

    @Test
    fun resourceFontFieldRendersNewScalarsAtFractionalBoundsAndUsesVisibleHitOffsets() {
        val state = TextFieldState("日", maxLength = 16)
        val backend = BitmapFieldFontBackend()
        val compactSize = IntSize(16, 20)
        val host = resourceFontHost(state, backend, compactSize)
        try {
            host.attach()
            host.frame(compactSize)
            host.dispatchTextInput(TextInputEvent.Character('한'.code))
            host.dispatchTextInput(TextInputEvent.Character(0x1F642))
            val typed = host.frame(compactSize)
            assertEquals("日한🙂", state.value)
            assertEquals(
                UiText.Literal("日한🙂"),
                typed.semantics
                    .single()
                    .semantics.label,
            )
            val glyphs =
                typed.drawCommands.filterIsInstance<DrawCommand.SampledImage>().filter { command ->
                    command.tint == ArgbColor(0xFFE0E0E0.toInt())
                }
            assertEquals(listOf(4.0f, 7.0f, 11.0f), glyphs.map { command -> command.destination.left })
            assertEquals(listOf(3.75f, 3.75f, 3.75f), glyphs.map { command -> command.destination.width })
            val scaled = rasterizeHeadless(typed.drawCommands, compactSize, scale = 2)
            assertEquals(0xFFE0E0E0.toInt(), scaled.argbAt(10, 16))
            assertEquals(0xFF606060.toInt(), scaled.argbAt(8, 16))

            host.dispatchPointer(PointerEvent.Press(IntOffset(4, 10), PointerButton.Primary))
            host.dispatchTextInput(TextInputEvent.Character('A'.code))
            assertEquals("日A한🙂", state.value)
            host.frame(compactSize)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.End, 0))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            assertEquals("日한", state.value)
            host.frame(compactSize)
            host.detach()
            assertEquals(0, backend.closeCalls)
            state.value = "🙂日"
            host.attach()
            host.frame(compactSize)
            assertEquals(0, backend.closeCalls)
        } finally {
            host.close()
        }
        host.close()
        assertEquals(1, backend.closeCalls)
    }

    @Test
    fun resourceFontPreeditKeepsUnicodeCaretAndBlockBoundariesSeparateFromCommittedValue() {
        val state = TextFieldState("日", maxLength = 8)
        val backend = BitmapFieldFontBackend()
        val host = resourceFontHost(state, backend)
        try {
            host.attach()
            host.frame(fieldSize)
            host.dispatchTextInput(TextInputEvent.Preedit("🙂한", 2, listOf("🙂", "한"), 1))
            val composed = host.frame(fieldSize)
            assertEquals("日", state.value)
            assertEquals(
                listOf(IntRect(10, 15, 13, 16), IntRect(10, 5, 11, 16)),
                composed.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().map { command -> command.bounds },
            )
            host.dispatchTextInput(TextInputEvent.Character(0x1F642))
            assertEquals("日🙂", state.value)
            assertTrue(host.frame(fieldSize).drawCommands.none { command -> command is DrawCommand.FillRectangle })
            host.dispatchTextInput(TextInputEvent.Preedit("한", 0, listOf("한"), 0))
            host.dispatchPointer(PointerEvent.Press(IntOffset(fieldSize.width, 10), PointerButton.Primary))
            assertTrue(host.frame(fieldSize).drawCommands.none { command -> command is DrawCommand.FillRectangle })
        } finally {
            host.close()
        }
    }

    @Test
    fun editableRtlTextAndInlinePreeditKeepLogicalScalarOrderWithoutDisplayShaping() {
        val state = TextFieldState("אב🙂", maxLength = 4)
        val backend =
            object : MinecraftFontBackend by BitmapFieldFontBackend() {
                override fun visualGlyphs(
                    text: String,
                    rightToLeft: Boolean,
                ): List<MinecraftVisualGlyph> = error("Editable text must not call the display shaping backend.")
            }
        val fontDefinition = """{"providers":[{"type":"bitmap","file":"example:font/input.png","height":5,"ascent":5,"chars":["אב🙂_A"]}]}"""
        val host = resourceFontHost(state, backend, fontDefinition = fontDefinition)
        try {
            host.attach()
            val committed = host.frame(fieldSize)
            val glyphs =
                committed.drawCommands.filterIsInstance<DrawCommand.SampledImage>().filter { command ->
                    command.tint == ArgbColor(0xFFE0E0E0.toInt())
                }
            assertEquals(listOf(4f, 6f, 9f), glyphs.map { it.destination.left })
            glyphs.forEachIndexed { index, glyph -> assertEquals(-1, glyph.image.argbAt(index * 2, 0)) }
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Left, 0))
            host.dispatchTextInput(TextInputEvent.Preedit("🙂א", 2, listOf("🙂", "א"), 1))
            assertEquals(
                listOf(IntRect(13, 15, 15, 16), IntRect(13, 5, 14, 16)),
                host
                    .frame(fieldSize)
                    .drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .map { it.bounds },
            )
            assertEquals("אב🙂", state.value)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            assertEquals("א🙂", state.value)
            host.dispatchTextInput(TextInputEvent.Character('ב'.code))
            assertEquals("אב🙂", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun negativeSpacingPreservesSignedCommittedAndPreeditCaretMeasurement() {
        val state = TextFieldState("A🙂", maxLength = 3)
        val compactSize = IntSize(12, 20)
        val fontDefinition = """{"providers":[{"type":"space","advances":{"A":20,"🙂":-19,"_":0}}]}"""
        val host = resourceFontHost(state, BitmapFieldFontBackend(), compactSize, fontDefinition)
        try {
            host.attach()
            assertEquals(
                listOf(IntRect(5, 5, 6, 16)),
                host
                    .frame(compactSize)
                    .drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .map { it.bounds },
            )
            host.dispatchTextInput(TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
            assertEquals(
                emptyList<IntRect>(),
                host
                    .frame(compactSize)
                    .drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .map { it.bounds },
            )
            assertEquals("A🙂", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun negativeAdvanceMovesCaretBeforeTheTextOrigin() {
        val state = TextFieldState("A🙂", maxLength = 3)
        val fontDefinition = """{"providers":[{"type":"space","advances":{"A":2,"🙂":-3}}]}"""
        val host = resourceFontHost(state, BitmapFieldFontBackend(), fontDefinition = fontDefinition)
        try {
            host.attach()
            assertEquals(
                listOf(IntRect(3, 5, 4, 16)),
                host
                    .frame(fieldSize)
                    .drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .map { it.bounds },
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun negativePreeditAdvanceUnderlinesTheVisibleReversedSpan() {
        val state = TextFieldState("A", maxLength = 3)
        val fontDefinition = """{"providers":[{"type":"space","advances":{"A":1,"🙂":-10}}]}"""
        val host = resourceFontHost(state, BitmapFieldFontBackend(), fontDefinition = fontDefinition)
        try {
            host.attach()
            host.frame(fieldSize)
            host.dispatchTextInput(TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
            assertEquals(
                listOf(IntRect(0, 15, 5, 16)),
                host
                    .frame(fieldSize)
                    .drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .map { it.bounds },
            )
            assertEquals("A", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun negativeAdvanceClickUsesTheSignedMidpointAndPreservesScalarBoundaries() {
        val state = TextFieldState("A🙂", maxLength = 3)
        val fontDefinition = """{"providers":[{"type":"space","advances":{"A":4,"🙂":-4}}]}"""
        val host = resourceFontHost(state, BitmapFieldFontBackend(), fontDefinition = fontDefinition)
        try {
            host.attach()
            host.frame(fieldSize)
            host.dispatchPointer(PointerEvent.Press(IntOffset(6, 10), PointerButton.Primary))
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            assertEquals("A", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun nonFiniteFontMetricsKeepScalarEditingAndCompositionBounded() {
        val fontDefinition = """{"providers":[{"type":"ttf","file":"example:input.ttf"}]}"""
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE, -Float.MAX_VALUE).forEach { advance ->
            listOf(false, true).forEach { saturatingCeil ->
                val state = TextFieldState("A🙂", maxLength = 6)
                val backend = NumericFieldFontBackend(mapOf('A'.code to advance, 0x1F642 to 1f, '_'.code to 1f))
                val host = resourceFontHost(state, backend, fontDefinition = fontDefinition, saturatingCeil = saturatingCeil)
                try {
                    host.attach()
                    host.frame(fieldSize)
                    assertTrue(0 < backend.glyphCalls)
                    host.dispatchTextInput(TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
                    val composed = host.frame(fieldSize)
                    composed.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().forEach { rectangle ->
                        assertTrue(0 <= rectangle.bounds.left && rectangle.bounds.right <= fieldSize.width)
                    }
                    host.dispatchPointer(PointerEvent.Press(IntOffset(8, 10), PointerButton.Primary))
                    host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.End, 0))
                    host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
                    assertEquals("A", state.value)
                    host.dispatchTextInput(TextInputEvent.Character(0x1F642))
                    assertEquals("A🙂", state.value)
                    host.frame(fieldSize)
                } finally {
                    host.close()
                }
            }
        }
    }

    private fun resourceFontHost(
        state: TextFieldState,
        backend: MinecraftFontBackend,
        size: IntSize = fieldSize,
        fontDefinition: String? = null,
        saturatingCeil: Boolean = false,
    ): MinecraftUiHost {
        val normal = """{"providers":[{"type":"bitmap","file":"example:font/input.png","height":8,"ascent":7,"chars":["日한🙂_A"]}]}"""
        val compact = fontDefinition ?: """{"providers":[{"type":"bitmap","file":"example:font/input.png","height":5,"ascent":5,"chars":["日한🙂_A"]}]}"""
        val source =
            MinecraftMemoryFontAssetSource(
                "field fonts",
                mapOf(
                    "assets/minecraft/font/default.json" to normal.encodeToByteArray(),
                    "assets/example/font/compact.json" to compact.encodeToByteArray(),
                    "assets/example/textures/font/input.png" to byteArrayOf(1),
                    "assets/example/font/input.ttf" to byteArrayOf(1),
                ),
            )
        val snapshot =
            MinecraftFontSnapshot.load(
                listOf(source),
                MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, packFormat = 0, saturatingCeil = saturatingCeil),
            )
        return createMinecraftUiHost(
            ScreenDefinition("resource font field") {
                TextField(state, size, ResourceId("example", "compact"), modifier = Modifier.Empty.initialFocus())
            },
            MinecraftProfileFixture.create(fontSnapshot = snapshot),
            MinecraftFontBackendFactory { backend },
        )
    }

    /**
     * Supplies detached native-like metrics to exercise editor geometry without native libraries or a GPU.
     */
    private class NumericFieldFontBackend(
        private val advances: Map<Int, Float>,
    ) : MinecraftFontBackend by BitmapFieldFontBackend() {
        var glyphCalls = 0
            private set

        override fun openTrueType(
            bytes: ByteArray,
            settings: MinecraftTrueTypeSettings,
        ): MinecraftTrueTypeFace =
            object : MinecraftTrueTypeFace {
                override fun glyph(codePoint: Int): MinecraftFontGlyph? {
                    glyphCalls += 1
                    return advances[codePoint]?.let { MinecraftFontGlyph(it, 0f, 0f, 0f, 0f, null) }
                }

                override fun close() = Unit
            }
    }

    /**
     * Supplies a synthetic decoded sheet with distinct advances and fractional logical cell widths for editor tests.
     */
    private class BitmapFieldFontBackend : MinecraftFontBackend {
        private val image =
            createDrawImage(
                IntSize(30, 8),
                IntArray(30 * 8).also { pixels ->
                    listOf(0, 2, 4, 1, 0).forEachIndexed { column, ink -> pixels[column * 6 + ink] = -1 }
                },
            )
        var closeCalls = 0
            private set

        override fun decodePng(bytes: ByteArray): DrawImage = image

        override fun openTrueType(
            bytes: ByteArray,
            settings: MinecraftTrueTypeSettings,
        ): MinecraftTrueTypeFace = error("The bitmap editor fixture does not open TrueType faces.")

        override fun close() {
            closeCalls += 1
        }
    }
}
