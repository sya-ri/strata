package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.text.TextWrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies visual caret affinity at soft-wrap boundaries and nearest-line selection across spacing gaps.
 */
internal class MinecraftTextAreaNavigationTest {
    @Test
    fun leftAndRightTraverseBothSoftBoundaryPositionsBeforeCrossingAnotherScalar() {
        val size = IntSize(15, 35)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("AB🙂한CD")
                tree.update(fixture.description(state, size, wrap = TextWrap.Character))
                fixture.frame(tree, size)
                fixture.key(tree, KeyCode.Home, size, KeyboardModifiers(control = true))
                assertCaret(fixture.key(tree, KeyCode.End, size), 10, 4)
                assertCaret(fixture.key(tree, KeyCode.Right, size), 4, 13)
                assertCaret(fixture.key(tree, KeyCode.Left, size), 10, 4)
                assertCaret(fixture.key(tree, KeyCode.Left, size), 7, 4)
                assertCaret(fixture.key(tree, KeyCode.Right, size), 10, 4)
                assertCaret(fixture.key(tree, KeyCode.Right, size), 4, 13)
                assertCaret(fixture.key(tree, KeyCode.Right, size), 7, 13)
                assertCaret(fixture.key(tree, KeyCode.Left, size), 4, 13)
                fixture.input(tree, TextInputEvent.Character('日'.code), size)
                assertEquals("AB日🙂한CD", state.value)
            }
        }
    }

    @Test
    fun verticalPageAndFarEdgePointerMovementDoNotStickOnTheFollowingSoftLine() {
        val size = IntSize(15, 35)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("AB🙂한CD")
                tree.update(fixture.description(state, size, wrap = TextWrap.Character))
                fixture.frame(tree, size)
                assertCaret(fixture.key(tree, KeyCode.Up, size), 10, 13)
                assertCaret(fixture.key(tree, KeyCode.Up, size), 10, 4)
                assertCaret(fixture.key(tree, KeyCode.Down, size), 10, 13)
                assertCaret(fixture.key(tree, KeyCode.Down, size), 10, 22)
                assertCaret(fixture.key(tree, KeyCode.PageUp, size), 10, 4)
                assertCaret(fixture.key(tree, KeyCode.PageDown, size), 10, 22)
                tree.dispatchPointer(PointerEvent.Press(IntOffset(10, 5), PointerButton.Primary))
                assertCaret(fixture.frame(tree, size), 10, 4)
                assertCaret(fixture.key(tree, KeyCode.Down, size), 10, 13)
                assertCaret(fixture.key(tree, KeyCode.Home, size), 4, 13)
                assertCaret(fixture.key(tree, KeyCode.Left, size), 10, 4)
            }
        }
    }

    @Test
    fun hardBreaksDoNotCreateAnExtraAffinityStop() {
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("A\nB")
                tree.update(fixture.description(state))
                fixture.frame(tree)
                fixture.key(tree, KeyCode.Home, modifiers = KeyboardModifiers(control = true))
                assertCaret(fixture.key(tree, KeyCode.End), 7, 4)
                assertCaret(fixture.key(tree, KeyCode.Right), 4, 13)
                assertCaret(fixture.key(tree, KeyCode.Left), 7, 4)
                fixture.key(tree, KeyCode.Right)
                fixture.input(tree, TextInputEvent.Character('日'.code))
                assertEquals("A\n日B", state.value)
            }
        }
    }

    @Test
    fun reflowPreservesVisualAffinityAndPreeditZeroCaretUsesTheCommittedEdge() {
        val size = IntSize(15, 35)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("ABCD")
                tree.update(fixture.description(state, size, wrap = TextWrap.Character))
                fixture.frame(tree, size)
                fixture.key(tree, KeyCode.Home, size, KeyboardModifiers(control = true))
                assertCaret(fixture.key(tree, KeyCode.End, size), 10, 4)
                val wide = IntSize(21, 35)
                tree.update(fixture.description(state, wide, wrap = TextWrap.Character))
                assertCaret(fixture.frame(tree, wide), 10, 4)
                tree.update(fixture.description(state, size, wrap = TextWrap.Character))
                assertCaret(fixture.frame(tree, size), 10, 4)
                val composition = fixture.input(tree, TextInputEvent.Preedit("🙂", 0, listOf("🙂"), 0), size)
                assertCaret(composition, 10, 4)
                assertEquals("ABCD", state.value)
                state.value = "AB日D"
                assertCaret(fixture.frame(tree, size), 4, 13)
            }
        }
    }

    @Test
    fun exactFitLineEndKeepsOneCaretPixelInsideWithoutChangingWrapOrHitMetrics() {
        val size = IntSize(14, 26)
        MinecraftTextAreaFixture().use { fixture ->
            UiTree().use { tree ->
                val state = TextAreaState("ABCD")
                tree.update(fixture.description(state, size, wrap = TextWrap.Character))
                fixture.frame(tree, size)
                fixture.key(tree, KeyCode.Home, size, KeyboardModifiers(control = true))
                assertCaret(fixture.key(tree, KeyCode.End, size), 9, 4)
                assertCaret(fixture.key(tree, KeyCode.Right, size), 4, 13)
                fixture.key(tree, KeyCode.Left, size)
                fixture.input(tree, TextInputEvent.Character('日'.code), size)
                assertEquals("AB日CD", state.value)
            }
        }
    }

    @Test
    fun spacingGapChoosesTheNearerLineOnBothSidesOfItsMidpointBeforeAndAfterScroll() {
        val size = IntSize(32, 35)
        MinecraftTextAreaFixture().use { fixture ->
            for (scroll in listOf(0.0, 7.0)) {
                for ((y, expected) in listOf(10 to "日A\nB\nC\nD", 12 to "日A\nB\nC\nD", 13 to "A\n日B\nC\nD")) {
                    UiTree().use { tree ->
                        val state = TextAreaState("A\nB\nC\nD")
                        tree.update(fixture.description(state, size, lineSpacing = 6))
                        fixture.frame(tree, size)
                        state.scrollState.scrollTo(scroll)
                        fixture.frame(tree, size)
                        tree.dispatchPointer(PointerEvent.Press(IntOffset(4, 4 + y - scroll.toInt()), PointerButton.Primary))
                        fixture.frame(tree, size)
                        fixture.input(tree, TextInputEvent.Character('日'.code), size)
                        assertEquals(expected, state.value)
                    }
                }
            }
        }
    }

    private fun assertCaret(
        commands: List<DrawCommand>,
        left: Int,
        top: Int,
    ) {
        assertEquals(IntRect(left, top, left + 1, top + 9), commands.filterIsInstance<DrawCommand.FillRectangle>().last().bounds)
    }
}
