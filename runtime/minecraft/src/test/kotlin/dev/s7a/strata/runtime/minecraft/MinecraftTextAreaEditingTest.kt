package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises scalar editing, canonical input, committed semantics, and multiline composition through the public component.
 */
internal class MinecraftTextAreaEditingTest {
    @Test
    fun scalarEditingAndEnterPreserveSupplementaryCharactersAndUtf16Capacity() {
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("日🙂\n한", maxLength = 8)
                tree.update(fixture.description(state))
                fixture.frame(tree)
                fixture.key(tree, KeyCode.Left)
                fixture.key(tree, KeyCode.Backspace)
                assertEquals("日🙂한", state.value)
                fixture.key(tree, KeyCode.Left)
                fixture.key(tree, KeyCode.Delete)
                assertEquals("日한", state.value)
                fixture.key(tree, KeyCode.Enter)
                fixture.input(tree, TextInputEvent.Character(0x1F642))
                assertEquals("日\n🙂한", state.value)
                fixture.input(tree, TextInputEvent.Character(0x1F642))
                assertEquals("日\n🙂🙂한", state.value)
                fixture.input(tree, TextInputEvent.Character(0x1F642))
                assertEquals("日\n🙂🙂한", state.value)
                fixture.key(tree, KeyCode.Backspace)
                assertEquals("日\n🙂한", state.value)
                assertSame(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Release(KeyCode.Enter, 0)))
                assertSame(InputResult.Ignored, tree.dispatchTextInput(TextInputEvent.Character(0x09)))
            }
        }
    }

    @Test
    fun everyCommittedHardBreakCanonicalizesToLfAndConsumesOneCodeUnit() {
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("🙂", maxLength = 9)
                tree.update(fixture.description(state))
                fixture.frame(tree)
                for (codePoint in listOf(0x0A, 0x0D, 0x0B, 0x0C, 0x85, 0x2028, 0x2029)) {
                    fixture.input(tree, TextInputEvent.Character(codePoint))
                }
                assertEquals("🙂" + "\n".repeat(7), state.value)
                fixture.key(tree, KeyCode.Enter)
                assertEquals(9, state.value.length)
                fixture.key(tree, KeyCode.Backspace)
                assertEquals("🙂" + "\n".repeat(6), state.value)
            }
        }
    }

    @Test
    fun verticalNavigationRetainsPreferredColumnAcrossShortRowsAndPageMovement() {
        val control = KeyboardModifiers(control = true)
        val cases =
            listOf(
                listOf(KeyboardEvent.Press(KeyCode.Up, 0), KeyboardEvent.Press(KeyCode.Up, 0)) to "ABCD日\nA\nABCD",
                listOf(KeyboardEvent.Press(KeyCode.Home, 0)) to "ABCD\nA\n日ABCD",
                listOf(KeyboardEvent.Press(KeyCode.PageUp, 0)) to "ABCD日\nA\nABCD",
                listOf(KeyboardEvent.Press(KeyCode.Home, 0, control), KeyboardEvent.Press(KeyCode.PageDown, 0)) to "ABCD\nA\n日ABCD",
                listOf(KeyboardEvent.Press(KeyCode.Home, 0, control), KeyboardEvent.Press(KeyCode.Right, 0), KeyboardEvent.Press(KeyCode.Down, 0), KeyboardEvent.Press(KeyCode.Down, 0)) to "ABCD\nA\nA日BCD",
                listOf(KeyboardEvent.Press(KeyCode.Home, 0, control), KeyboardEvent.Press(KeyCode.End, 0, control)) to "ABCD\nA\nABCD日",
            )
        MinecraftTextAreaFixture().use { fixture ->
            for ((keys, expected) in cases) {
                UiTree().use { tree ->
                    val state = TextAreaState("ABCD\nA\nABCD")
                    tree.update(fixture.description(state))
                    fixture.frame(tree)
                    keys.forEach { event ->
                        tree.dispatchKeyboard(event)
                        fixture.frame(tree)
                    }
                    fixture.input(tree, TextInputEvent.Character('日'.code))
                    assertEquals(expected, state.value)
                }
            }
        }
    }

    @Test
    fun compositionNormalizesCrlfCaretAndSplitBlocksWithoutCommittingText() {
        val size = IntSize(32, 44)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("日", maxLength = 8)
                tree.update(fixture.description(state, size))
                fixture.frame(tree, size)
                val event = TextInputEvent.Preedit("🙂\r\n한\u2028A", 4, listOf("🙂\r", "\n한\u2028A"), 1)
                val composed = fixture.input(tree, event, size)
                assertEquals("日", state.value)
                assertEquals(
                    listOf(IntRect(4, 21, 7, 22), IntRect(4, 30, 7, 31), IntRect(4, 13, 5, 22)),
                    composed.filterIsInstance<DrawCommand.FillRectangle>().map { it.bounds },
                )
                assertEquals(8, composed.filterIsInstance<DrawCommand.SampledImage>().size)
                assertEquals(
                    UiText.Literal("日"),
                    tree
                        .semantics()
                        .single()
                        .semantics.value,
                )
                fixture.input(tree, TextInputEvent.Character('한'.code), size)
                assertEquals("日한", state.value)
                assertEquals(1, fixture.frame(tree, size).filterIsInstance<DrawCommand.FillRectangle>().size)
            }
        }
    }

    @Test
    fun invalidCompositionPreservesPreviousPixelsCursorAndScrollAtomically() {
        val size = IntSize(17, 17)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("日\n한", maxLength = 8)
                tree.update(fixture.description(state, size))
                fixture.frame(tree, size)
                val original = fixture.input(tree, TextInputEvent.Preedit("🙂A", 2, listOf("🙂", "A"), 1), size)
                val scroll = state.scrollState.metrics
                val invalid =
                    listOf(
                        TextInputEvent.Preedit("🙂", 1, emptyList(), -1),
                        TextInputEvent.Preedit("🙂\uD800", 2, emptyList(), -1),
                        TextInputEvent.Preedit("🙂", 2, listOf("\uD83D", "\uDE42"), 1),
                        TextInputEvent.Preedit("🙂", 2, listOf("\u0001"), 0),
                        TextInputEvent.Preedit("AAAAAA", 6, emptyList(), -1),
                        TextInputEvent.Preedit("A", 1, listOf("A".repeat(11)), 0),
                        TextInputEvent.Preedit("A", 1, List(12) { "" }, 0),
                    )
                for (event in invalid) {
                    assertSame(InputResult.Ignored, tree.dispatchTextInput(event))
                    assertEquals("日\n한", state.value)
                    assertEquals(scroll, state.scrollState.metrics)
                    assertEquals(original, fixture.frame(tree, size))
                }
                fixture.input(tree, TextInputEvent.Character('A'.code), size)
                assertEquals("日\n한A", state.value)
            }
        }
    }

    @Test
    fun semanticsExposeCommittedValueAndDisabledEditorsDoNotOwnTextInput() {
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("日\n한")
                tree.update(fixture.description(state))
                fixture.frame(tree)
                fixture.input(tree, TextInputEvent.Preedit("🙂", 2, listOf("🙂"), 0))
                val semantics = tree.semantics().single().semantics
                assertSame(SemanticsRole.TextArea, semantics.role)
                assertEquals(UiText.Literal("日\n한"), semantics.value)
                assertNull(semantics.label)
                assertFalse(semantics.disabled)
                tree.update(fixture.description(state, enabled = false))
                val disabled = fixture.frame(tree)
                assertTrue(
                    tree
                        .semantics()
                        .single()
                        .semantics.disabled,
                )
                assertTrue(disabled.none { it is DrawCommand.FillRectangle })
                assertSame(InputResult.Ignored, tree.dispatchTextInput(TextInputEvent.Character('A'.code)))
                assertSame(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
                tree.update(fixture.description(state))
                fixture.frame(tree)
                tree.dispatchPointer(PointerEvent.Press(IntOffset(4, 4), PointerButton.Primary))
                fixture.frame(tree)
                fixture.input(tree, TextInputEvent.Character('A'.code))
                assertEquals("A日\n한", state.value)
            }
        }
    }
}
