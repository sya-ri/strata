@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.focusable
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies shared primary-pointer and focused keyboard activation through the retained tree.
 */
internal class ActivationModifierTest {
    @Test
    fun primaryPointerAndFocusedEnterSpacePressesShareOneAction() {
        var activations = 0
        val tree = tree(Modifier.Empty.size(10, 10).onActivate { activations += 1 })
        try {
            assertEquals(
                InputResult.Ignored,
                tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Secondary)),
            )
            assertEquals(
                InputResult.Ignored,
                tree.dispatchPointer(PointerEvent.Release(IntOffset(1, 1), PointerButton.Primary)),
            )
            assertEquals(0, activations)

            assertEquals(
                InputResult.Consumed,
                tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
            )
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 7)))
            val modifiedSpace = KeyboardEvent.Press(KeyCode.Space, 8, KeyboardModifiers(shift = true, control = true, alt = true, superKey = true))
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(modifiedSpace))
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(modifiedSpace))
            assertEquals(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Release(KeyCode.Space, 8)))
            assertEquals(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Escape, 9)))
            assertEquals(4, activations)
        } finally {
            tree.close()
        }
    }

    @Test
    fun disabledActivationReturnsTheReceiverAndAddsNoInputBehavior() {
        var activations = 0
        val base = Modifier.Empty.size(10, 10)
        val disabled = base.onActivate(enabled = false) { activations += 1 }
        assertSame(base, disabled)
        assertEquals(base.elements(), disabled.elements())

        val tree = tree(disabled)
        try {
            assertEquals(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(
                InputResult.Ignored,
                tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
            )
            assertEquals(0, activations)
        } finally {
            tree.close()
        }

        val focusOnly = tree(base.focusable().onActivate(enabled = false) { activations += 1 })
        try {
            assertEquals(InputResult.Consumed, focusOnly.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(InputResult.Ignored, focusOnly.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
            assertEquals(0, activations)
        } finally {
            focusOnly.close()
        }
    }

    @Test
    fun retainedActivationReadsUpdatedCallbacksWithoutFrameInvalidation() {
        val observed = ArrayList<String>()
        val tree = UiTree()

        fun update(value: String) {
            tree.update(
                evaluateComponentTree {
                    Spacer(modifier = Modifier.Empty.size(10, 10).onActivate { observed += value })
                },
            )
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
        }

        try {
            update("first")
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))

            update("second")
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Space, 0)))
            assertEquals(
                InputResult.Consumed,
                tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
            )
            assertEquals(listOf("first", "second", "second"), observed)
        } finally {
            tree.close()
        }
    }

    @Test
    fun activationFailurePreservesIdentityAndPoisonsTheTree() {
        val primary = IllegalArgumentException("activation")
        val tree = tree(Modifier.Empty.size(10, 10).onActivate { throw primary })
        assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))

        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0))
            }

        assertSame(primary, thrown)
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    private fun tree(modifier: Modifier): UiTree =
        UiTree().also { tree ->
            tree.update(evaluateComponentTree { Spacer(modifier = modifier) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
        }
}
