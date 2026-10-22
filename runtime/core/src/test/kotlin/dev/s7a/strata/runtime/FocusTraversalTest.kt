@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.focusable
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.size
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies cyclic Tab traversal through accepting logical owners in retained paint order.
 */
internal class FocusTraversalTest {
    @Test
    fun tabTraversesDeclaredSiblingsForwardAndShiftReversesWithWrap() {
        val transitions = ArrayList<Transition>()
        rowTree(
            target(Target.First, transitions),
            target(Target.Second, transitions),
            target(Target.Third, transitions),
        ).use { tree ->
            assertEquals(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
            assertEquals(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Release(KeyCode.Tab, 0)))
            assertEquals(
                InputResult.Consumed,
                tree.dispatchKeyboard(
                    KeyboardEvent.Press(
                        KeyCode.Tab,
                        1,
                        KeyboardModifiers(control = true, alt = true, superKey = true),
                    ),
                ),
            )
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 1)))
            assertEquals(
                InputResult.Consumed,
                tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 1, KeyboardModifiers(shift = true))),
            )
            assertEquals(
                InputResult.Consumed,
                tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 1, KeyboardModifiers(shift = true))),
            )
            assertEquals(
                listOf(
                    Transition(Target.First, FocusEvent.Gained),
                    Transition(Target.First, FocusEvent.Lost),
                    Transition(Target.Second, FocusEvent.Gained),
                    Transition(Target.Second, FocusEvent.Lost),
                    Transition(Target.First, FocusEvent.Gained),
                    Transition(Target.First, FocusEvent.Lost),
                    Transition(Target.Third, FocusEvent.Gained),
                ),
                transitions,
            )
        }
    }

    @Test
    fun traversalVisitsFocusableParentBeforeDeclaredDescendants() {
        val transitions = ArrayList<Transition>()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Stack(modifier = target(Target.Parent, transitions, width = 20)) {
                    Row {
                        Spacer(modifier = target(Target.First, transitions))
                        Spacer(modifier = target(Target.Second, transitions))
                    }
                }
            },
        )
        tree.measure(Constraints.fixed(20, 10))
        tree.layout()
        tree.use { _ ->
            repeat(3) {
                assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            }
            assertEquals(
                listOf(
                    Transition(Target.Parent, FocusEvent.Gained),
                    Transition(Target.Parent, FocusEvent.Lost),
                    Transition(Target.First, FocusEvent.Gained),
                    Transition(Target.First, FocusEvent.Lost),
                    Transition(Target.Second, FocusEvent.Gained),
                ),
                transitions,
            )
        }
    }

    @Test
    fun focusedHandlerRunsBeforeTraversalAndMaySuppressIt() {
        val transitions = ArrayList<Transition>()
        val handled = ArrayList<KeyboardEvent.Press>()
        var consume = true
        val first =
            target(Target.First, transitions)
                .initialFocus()
                .onKeyPress { event ->
                    handled += event
                    if (consume) InputResult.Consumed else InputResult.Ignored
                }
        rowTree(first, target(Target.Second, transitions)).use { tree ->
            val tab = KeyboardEvent.Press(KeyCode.Tab, 4)
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(tab))
            assertEquals(listOf(Transition(Target.First, FocusEvent.Gained)), transitions)

            consume = false
            assertEquals(InputResult.Consumed, tree.dispatchKeyboard(tab))
            assertEquals(listOf(tab, tab), handled)
            assertEquals(
                listOf(
                    Transition(Target.First, FocusEvent.Gained),
                    Transition(Target.First, FocusEvent.Lost),
                    Transition(Target.Second, FocusEvent.Gained),
                ),
                transitions,
            )
        }
    }

    @Test
    fun oneEligibleOwnerConsumesTraversalWithoutRepeatingTransitionsAndZeroIgnores() {
        val transitions = ArrayList<Transition>()
        rowTree(target(Target.First, transitions).focusable()).use { single ->
            assertEquals(InputResult.Consumed, single.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(InputResult.Consumed, single.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(
                InputResult.Consumed,
                single.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0, KeyboardModifiers(shift = true))),
            )
            assertEquals(listOf(Transition(Target.First, FocusEvent.Gained)), transitions)
        }

        rowTree(Modifier.size(10, 10)).use { empty ->
            assertEquals(InputResult.Ignored, empty.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
        }
    }

    @Test
    fun placedNonAcceptingOwnerRemainsTheDirectionalAnchor() {
        assertDisabledAnchor(reverse = false, expected = Target.Second)
        assertDisabledAnchor(reverse = true, expected = Target.Third)
    }

    private fun assertDisabledAnchor(
        reverse: Boolean,
        expected: Target,
    ) {
        val transitions = ArrayList<Transition>()
        val tree = UiTree()

        fun update(first: Modifier) {
            tree.update(
                evaluateComponentTree {
                    Row {
                        Spacer(modifier = first)
                        Spacer(modifier = target(Target.Second, transitions))
                        Spacer(modifier = target(Target.Third, transitions))
                    }
                },
            )
            tree.measure(Constraints.fixed(30, 10))
            tree.layout()
        }

        tree.use { _ ->
            update(target(Target.First, transitions).initialFocus())
            update(Modifier.size(10, 10))
            assertEquals(
                InputResult.Consumed,
                tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0, KeyboardModifiers(shift = reverse))),
            )
            assertEquals(
                listOf(
                    Transition(Target.First, FocusEvent.Gained),
                    Transition(expected, FocusEvent.Gained),
                ),
                transitions,
            )
        }
    }

    private fun rowTree(vararg modifiers: Modifier): UiTree =
        UiTree().also { tree ->
            tree.update(
                evaluateComponentTree {
                    Row {
                        modifiers.forEach { modifier -> Spacer(modifier = modifier) }
                    }
                },
            )
            tree.measure(Constraints.fixed(modifiers.size * 10, 10))
            tree.layout()
        }

    private fun target(
        target: Target,
        transitions: MutableList<Transition>,
        width: Int = 10,
    ): Modifier =
        Modifier
            .size(width, 10)
            .onFocusChanged { event -> transitions += Transition(target, event) }

    private enum class Target {
        Parent,
        First,
        Second,
        Third,
    }

    private data class Transition(
        val target: Target,
        val event: FocusEvent,
    )
}
