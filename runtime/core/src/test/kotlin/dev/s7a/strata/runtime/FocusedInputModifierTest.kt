@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.onCharacterInput
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onKeyEvent
import dev.s7a.strata.modifier.onKeyPress
import dev.s7a.strata.modifier.onKeyRelease
import dev.s7a.strata.modifier.onPreedit
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onTextInput
import dev.s7a.strata.modifier.size
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies focused keyboard, committed-text, preedit, and focus modifiers through the retained pipeline.
 */
internal class FocusedInputModifierTest {
    @Test
    fun initialTargetReceivesEveryTypedEventInInnerToOuterOrder() {
        val observed = ArrayList<Observation>()
        val modifier =
            Modifier.Empty
                .size(10, 10)
                .onKeyEvent { event ->
                    observed += Observation.KeyEvery(event)
                    InputResult.Ignored
                }.onKeyPress { event ->
                    observed += Observation.KeyPress(event)
                    InputResult.Ignored
                }.onKeyRelease { event ->
                    observed += Observation.KeyRelease(event)
                    InputResult.Ignored
                }.onTextInput { event ->
                    observed += Observation.TextEvery(event)
                    InputResult.Ignored
                }.onCharacterInput { event ->
                    observed += Observation.Character(event)
                    InputResult.Ignored
                }.onPreedit { event ->
                    observed += Observation.Preedit(event)
                    InputResult.Ignored
                }.initialFocus()
                .onFocusChanged { event -> observed += Observation.Focus(event) }
        val tree = tree(modifier)
        assertEquals(listOf(Observation.Focus(FocusEvent.Gained)), observed)

        observed.clear()
        val modifiers = KeyboardModifiers(shift = true, control = true)
        val press = KeyboardEvent.Press(KeyCode.Enter, 7, modifiers)
        val release = KeyboardEvent.Release(KeyCode.Enter, 7, modifiers)
        val character = TextInputEvent.Character(0x1F642)
        val preedit = TextInputEvent.Preedit("compose", 3, listOf("com", "pose"), 1)
        assertEquals(InputResult.Ignored, tree.dispatchKeyboard(press))
        assertEquals(InputResult.Ignored, tree.dispatchKeyboard(release))
        assertEquals(InputResult.Ignored, tree.dispatchTextInput(character))
        assertEquals(InputResult.Ignored, tree.dispatchTextInput(preedit))
        assertEquals(
            listOf(
                Observation.KeyPress(press),
                Observation.KeyEvery(press),
                Observation.KeyRelease(release),
                Observation.KeyEvery(release),
                Observation.Character(character),
                Observation.TextEvery(character),
                Observation.Preedit(preedit),
                Observation.TextEvery(preedit),
            ),
            observed,
        )
        tree.close()
    }

    @Test
    fun consumingPrimaryPressMovesFocusAndDetachClearsItOnce() {
        val transitions = ArrayList<Transition>()
        val keyTargets = ArrayList<Target>()
        val first = focusableModifier(Target.First, transitions, keyTargets)
        val second = focusableModifier(Target.Second, transitions, keyTargets)
        val tree =
            UiTree().also { tree ->
                tree.update(
                    evaluateComponentTree {
                        Row {
                            Spacer(modifier = first)
                            Spacer(modifier = second)
                        }
                    },
                )
                tree.measure(Constraints.fixed(20, 10))
                tree.layout()
            }

        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
        assertEquals(listOf(Transition(Target.First, FocusEvent.Gained)), transitions)
        assertEquals(InputResult.Consumed, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
        assertEquals(listOf(Target.First), keyTargets)

        tree.dispatchPointer(PointerEvent.Press(IntOffset(11, 1), PointerButton.Primary))
        assertEquals(
            listOf(
                Transition(Target.First, FocusEvent.Gained),
                Transition(Target.First, FocusEvent.Lost),
                Transition(Target.Second, FocusEvent.Gained),
            ),
            transitions,
        )
        tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0))
        assertEquals(listOf(Target.First, Target.Second), keyTargets)

        tree.clearInputState()
        tree.clearInputState()
        assertEquals(
            listOf(
                Transition(Target.First, FocusEvent.Gained),
                Transition(Target.First, FocusEvent.Lost),
                Transition(Target.Second, FocusEvent.Gained),
                Transition(Target.Second, FocusEvent.Lost),
            ),
            transitions,
        )
        assertEquals(InputResult.Ignored, tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
        tree.close()
    }

    @Test
    fun duplicateInitialTargetsFailLayoutAndCallbackFailurePreservesIdentity() {
        val duplicate = UiTree()
        duplicate.update(
            evaluateComponentTree {
                Row {
                    Spacer(modifier = Modifier.Empty.size(10, 10).initialFocus())
                    Spacer(modifier = Modifier.Empty.size(10, 10).initialFocus())
                }
            },
        )
        duplicate.measure(Constraints.fixed(20, 10))
        assertThrows(IllegalStateException::class.java) { duplicate.layout() }
        assertEquals(TreeState.Poisoned, duplicate.state)
        duplicate.close()

        val primary = IllegalArgumentException("focused callback")
        val failing =
            tree(
                Modifier.Empty
                    .size(10, 10)
                    .initialFocus()
                    .onKeyPress { throw primary },
            )
        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                failing.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0))
            }
        assertSame(primary, thrown)
        assertEquals(TreeState.Poisoned, failing.state)
        failing.close()
    }

    private fun focusableModifier(
        target: Target,
        transitions: MutableList<Transition>,
        keys: MutableList<Target>,
    ): Modifier =
        Modifier.Empty
            .size(10, 10)
            .onPress {}
            .onKeyPress {
                keys += target
                InputResult.Consumed
            }.onFocusChanged { event -> transitions += Transition(target, event) }

    private fun tree(modifier: Modifier): UiTree =
        UiTree().also { tree ->
            tree.update(evaluateComponentTree { Spacer(modifier = modifier) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
        }

    private sealed interface Observation {
        data class KeyEvery(
            val event: KeyboardEvent,
        ) : Observation

        data class KeyPress(
            val event: KeyboardEvent.Press,
        ) : Observation

        data class KeyRelease(
            val event: KeyboardEvent.Release,
        ) : Observation

        data class TextEvery(
            val event: TextInputEvent,
        ) : Observation

        data class Character(
            val event: TextInputEvent.Character,
        ) : Observation

        data class Preedit(
            val event: TextInputEvent.Preedit,
        ) : Observation

        data class Focus(
            val event: FocusEvent,
        ) : Observation
    }

    private enum class Target {
        First,
        Second,
    }

    private data class Transition(
        val target: Target,
        val event: FocusEvent,
    )
}
