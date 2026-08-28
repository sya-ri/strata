package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextStyle
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
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftTextFieldFixture.fieldSize
import dev.s7a.strata.runtime.minecraft.MinecraftTextFieldFixture.host
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the ASCII fixture's field geometry, styling, basic editing, and retained invalidation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftTextFieldTest {
    @Test
    fun unfocusedFieldUsesExactSpriteEditBoxColorsAndSemantics() {
        val state = TextFieldState("A")
        val host = host(state)
        host.attach()

        val frame = host.frame(fieldSize)
        assertEquals(fieldSize, frame.size)
        assertEquals(3, frame.drawCommands.size)
        val sprite = frame.drawCommands.first() as DrawCommand.BlitImage
        assertEquals(fieldSize, sprite.image.size)
        assertEquals(
            SemanticsRole.TextField,
            frame.semantics
                .single()
                .semantics.role,
        )
        assertEquals(
            UiText.Literal("A"),
            frame.semantics
                .single()
                .semantics.label,
        )
        val image = rasterizeHeadless(frame.drawCommands, fieldSize)
        assertEquals(0xFFE0E0E0.toInt(), image.argbAt(4, 6))
        assertEquals(0xFF383838.toInt(), image.argbAt(5, 7))
        host.close()
    }

    @Test
    fun explicitlySizedFieldUsesVerticalNineSliceAndCenteredText() {
        val state = TextFieldState("A")
        val compactSize = IntSize(200, 15)
        val host = host(state, size = compactSize)
        try {
            host.attach()
            val frame = host.frame(compactSize)
            val commands = frame.drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(
                listOf(
                    IntRect(0, 0, 200, 1),
                    IntRect(0, 1, 200, 14),
                    IntRect(0, 14, 200, 15),
                ),
                commands.take(3).map { command -> command.destination },
            )
            assertEquals(
                listOf(
                    IntRect(0, 0, 200, 1),
                    IntRect(0, 1, 200, 14),
                    IntRect(0, 19, 200, 20),
                ),
                commands.take(3).map { command -> command.source },
            )
            val image = rasterizeHeadless(frame.drawCommands, compactSize)
            assertEquals(0xFFE0E0E0.toInt(), image.argbAt(4, 3))
            assertEquals(0xFF383838.toInt(), image.argbAt(5, 4))
        } finally {
            host.close()
        }

        val invalid = host(state, size = IntSize(8, 15))
        assertThrows(IllegalArgumentException::class.java) { invalid.attach() }
        invalid.close()
    }

    @Test
    fun focusedEmptyFieldUsesTheNativeAppendCursorGlyphAndSelectedTextStyle() {
        val compactSize = IntSize(200, 15)
        val state = TextFieldState("", maxLength = 16)
        val host = host(state, Modifier.Empty.initialFocus(), compactSize, TextStyle.Normal)
        try {
            host.attach()
            val commands = host.frame(compactSize).drawCommands
            assertTrue(commands.all { command -> command is DrawCommand.BlitImage })
            assertEquals(
                listOf(IntRect(5, 4, 13, 12), IntRect(4, 3, 12, 11)),
                commands.takeLast(2).map { command -> (command as DrawCommand.BlitImage).destination },
            )
            val cursorCommands = commands.takeLast(2).map { command -> command as DrawCommand.BlitImage }
            assertTrue(cursorCommands[0].image.copyArgb().contains(0xFF3F3F3F.toInt()))
            assertTrue(cursorCommands[1].image.copyArgb().contains(0xFFFFFFFF.toInt()))
        } finally {
            host.close()
        }
    }

    @Test
    fun primaryPressFocusesWithoutConsumingAndEditorHandlesTypedKeys() {
        val state = TextFieldState("AB", maxLength = 4)
        val host = host(state)
        host.attach()
        host.frame(fieldSize)

        assertSame(InputResult.Ignored, host.dispatchPointer(PointerEvent.Press(IntOffset(4, 10), PointerButton.Primary)))
        assertSame(InputResult.Consumed, host.dispatchTextInput(TextInputEvent.Character('C'.code)))
        assertEquals("CAB", state.value)
        assertSame(InputResult.Consumed, host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.End, 0)))
        assertSame(InputResult.Consumed, host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0)))
        assertEquals("CA", state.value)
        assertSame(InputResult.Consumed, host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Home, 0)))
        assertSame(InputResult.Consumed, host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Delete, 0)))
        assertEquals("A", state.value)

        val focused = host.frame(fieldSize)
        assertEquals(0xFF606060.toInt(), rasterizeHeadless(focused.drawCommands, fieldSize).argbAt(0, 0))
        host.close()
    }

    @Test
    fun focusedModifierCanOverrideBuiltInCharacterEditing() {
        val state = TextFieldState("A")
        var intercepted = 0
        val modifier =
            Modifier.Empty
                .initialFocus()
                .onCharacterInput {
                    intercepted += 1
                    InputResult.Consumed
                }
        val host = host(state, modifier)
        host.attach()
        host.frame(fieldSize)

        assertSame(InputResult.Consumed, host.dispatchTextInput(TextInputEvent.Character('B'.code)))
        assertEquals(1, intercepted)
        assertEquals("A", state.value)
        host.close()
    }

    @Test
    fun backspaceThenInsertionMovesTheCursorOnlyOnce() {
        val state = TextFieldState("ABC")
        val host = host(state, Modifier.Empty.initialFocus())
        try {
            host.attach()
            host.frame(fieldSize)
            host.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Backspace, 0))
            host.dispatchTextInput(TextInputEvent.Character('D'.code))
            assertEquals("ABD", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun pointerCursorPlacementStartsAtTheVisibleScrolledText() {
        val state = TextFieldState("AAAAAAA")
        val compactSize = IntSize(12, 20)
        val host = host(state, Modifier.Empty.initialFocus(), compactSize)
        try {
            host.attach()
            host.frame(compactSize)
            host.dispatchPointer(PointerEvent.Press(IntOffset(4, 10), PointerButton.Primary))
            host.dispatchTextInput(TextInputEvent.Character('B'.code))
            assertEquals("AAAAABAA", state.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun explicitDefaultFontOverloadsRetainTextAndFieldPixels() {
        val font = ResourceId("minecraft", "default")
        val textSize = IntSize(2, 9)
        val profile = MinecraftProfileFixture.create()
        val implicitText = createMinecraftUiHost(ScreenDefinition("implicit text") { Text("A") }, profile)
        val explicitText = createMinecraftUiHost(ScreenDefinition("explicit text") { Text("A", font = font) }, profile)
        val unresolvedText = createMinecraftUiHost(ScreenDefinition("unresolved text") { Text(UiText.Literal("A"), font = font) }, profile)
        val implicitField = host(TextFieldState("A"))
        val explicitField = createMinecraftUiHost(ScreenDefinition("explicit field") { TextField(TextFieldState("A"), font = font) }, profile)
        val sizedField = createMinecraftUiHost(ScreenDefinition("sized field") { TextField(TextFieldState("A"), fieldSize, font) }, profile)
        val hosts = listOf(implicitText, explicitText, unresolvedText, implicitField, explicitField, sizedField)
        try {
            hosts.forEach(MinecraftUiHost::attach)
            val textPixels = rasterizeHeadless(implicitText.frame(textSize).drawCommands, textSize).copyArgb()
            assertTrue(textPixels.contentEquals(rasterizeHeadless(explicitText.frame(textSize).drawCommands, textSize).copyArgb()))
            assertTrue(textPixels.contentEquals(rasterizeHeadless(unresolvedText.frame(textSize).drawCommands, textSize).copyArgb()))
            val fieldPixels = rasterizeHeadless(implicitField.frame(fieldSize).drawCommands, fieldSize).copyArgb()
            assertTrue(fieldPixels.contentEquals(rasterizeHeadless(explicitField.frame(fieldSize).drawCommands, fieldSize).copyArgb()))
            assertTrue(fieldPixels.contentEquals(rasterizeHeadless(sizedField.frame(fieldSize).drawCommands, fieldSize).copyArgb()))
        } finally {
            hosts.forEach(MinecraftUiHost::close)
        }
    }

    @Test
    fun externalStateWritesInvalidatePaintAndSemanticsOnce() {
        val state = TextFieldState("A")
        val host = host(state)
        host.attach()
        val first = host.frame(fieldSize)
        state.value = "B"
        val second = host.frame(fieldSize)
        assertEquals(
            UiText.Literal("B"),
            second.semantics
                .single()
                .semantics.label,
        )
        assertTrue(second.drawCommands != first.drawCommands)
        state.value = "B"
        val equal = host.frame(fieldSize)
        assertEquals(second.drawCommands, equal.drawCommands)
        host.close()
    }
}
