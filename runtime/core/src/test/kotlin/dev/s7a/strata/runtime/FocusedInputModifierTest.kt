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
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
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
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertNull(tree.currentTextInputFocus())

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
    fun retainedTargetAcceptanceChangesDeliverDistinctTransitionsWithoutMovingFocus() {
        val ownerTransitions = ArrayList<FocusEvent>()
        val targetTransitions = ArrayList<FocusEvent>()
        val modifier =
            Modifier.Empty
                .size(10, 10)
                .initialFocus()
                .onFocusChanged(ownerTransitions::add)
        val tree = UiTree()

        fun update(accepts: Boolean) {
            tree.update(evaluateComponentTree { Spacer(modifier = modifier.then(FocusAcceptanceElement(accepts, targetTransitions))) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
        }

        try {
            update(false)
            val owner = retainedFocusOwner(tree)
            assertNotNull(owner)
            assertEquals(emptyList<FocusEvent>(), targetTransitions)
            update(true)
            update(true)
            assertEquals(listOf(FocusEvent.Gained), targetTransitions)
            update(false)
            update(false)
            assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost), targetTransitions)
            update(true)
            assertSame(owner, retainedFocusOwner(tree))
            assertEquals(listOf(FocusEvent.Gained), ownerTransitions)
            tree.clearInputState()
            tree.clearInputState()
            assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost, FocusEvent.Gained, FocusEvent.Lost), targetTransitions)
            assertEquals(listOf(FocusEvent.Gained, FocusEvent.Lost), ownerTransitions)
            assertTrue(retainedFocusTargets(tree).isEmpty())
        } finally {
            tree.close()
        }
    }

    @Test
    fun editableCapabilityChangesReplaceOnlyTheCurrentDetachedFocusInterval() {
        val transitions = ArrayList<FocusEvent>()
        val modifier = Modifier.Empty.size(10, 10).initialFocus()
        val tree = UiTree()

        fun update(
            editable: Boolean,
            accepts: Boolean = true,
        ) {
            tree.update(evaluateComponentTree { Spacer(modifier = modifier.then(FocusAcceptanceElement(accepts, transitions, editable))) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
        }

        try {
            update(false)
            val owner = checkNotNull(retainedFocusOwner(tree))
            assertNull(tree.currentTextInputFocus())
            update(true)
            val first = checkNotNull(tree.currentTextInputFocus())
            update(true)
            tree.layout()
            assertSame(first, tree.currentTextInputFocus())
            update(false)
            assertNull(tree.currentTextInputFocus())
            update(true)
            val second = checkNotNull(tree.currentTextInputFocus())
            assertNotSame(first, second)
            update(true, accepts = false)
            assertNull(tree.currentTextInputFocus())
            update(true)
            assertNotSame(second, checkNotNull(tree.currentTextInputFocus()))
            assertSame(owner, retainedFocusOwner(tree))
            tree.clearInputState()
            assertNull(tree.currentTextInputFocus())
            tree.layout()
            assertNotSame(second, checkNotNull(tree.currentTextInputFocus()))
        } finally {
            tree.close()
        }
        assertNull(retainedTextInputFocus(tree))
        assertTrue(retainedTextInputTargets(tree).isEmpty())
    }

    @Test
    fun replacedFocusTargetsAreForgottenWithoutCallingDisposedNodes() {
        val transitions = ArrayList<FocusEvent>()
        val modifier = Modifier.Empty.size(10, 10).initialFocus()
        val tree = tree(modifier.then(FocusAcceptanceElement(true, transitions, editable = true)))
        val owner = checkNotNull(retainedFocusOwner(tree))
        val previousTarget = retainedFocusTargets(tree).first()
        val previousInterval = checkNotNull(tree.currentTextInputFocus())
        try {
            tree.update(evaluateComponentTree { Spacer(modifier = modifier) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
            assertSame(owner, retainedFocusOwner(tree))
            assertNull(tree.currentTextInputFocus())
            assertTrue(retainedFocusTargets(tree).none { it === previousTarget })
            assertEquals(listOf(FocusEvent.Gained), transitions)
            tree.update(evaluateComponentTree { Spacer(modifier = modifier.then(FocusAcceptanceElement(true, transitions, editable = true))) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
            assertNotSame(previousInterval, checkNotNull(tree.currentTextInputFocus()))
            assertEquals(listOf(FocusEvent.Gained, FocusEvent.Gained), transitions)
            tree.update(evaluateComponentTree { Spacer(modifier = modifier) })
            tree.clearInputState()
            assertNull(retainedFocusOwner(tree))
            assertNull(tree.currentTextInputFocus())
            assertTrue(retainedTextInputTargets(tree).isEmpty())
            assertTrue(retainedFocusTargets(tree).isEmpty())
            assertEquals(listOf(FocusEvent.Gained, FocusEvent.Gained), transitions)
        } finally {
            tree.close()
        }
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

    @Test
    fun closeReleasesFocusedOwnerWithoutSynthesizingAnotherTransition() {
        val transitions = ArrayList<FocusEvent>()
        val tree =
            tree(
                Modifier.Empty
                    .size(10, 10)
                    .initialFocus()
                    .onFocusChanged(transitions::add),
            )
        assertNotNull(retainedFocusOwner(tree))

        tree.close()

        assertNull(retainedFocusOwner(tree))
        assertTrue(retainedFocusTargets(tree).isEmpty())
        assertEquals(listOf(FocusEvent.Gained), transitions)
    }

    @Test
    fun poisonReleasesFocusedOwnerEvenWhenCleanupFails() {
        val primary = IllegalArgumentException("focused callback")
        val cleanup = IllegalStateException("dispose")
        val transitions = ArrayList<FocusEvent>()
        val probe =
            TestProbe(
                failingDisposeTag = TestProbe.ProbeId("root"),
                disposeFailure = cleanup,
            )
        val tree = UiTree()
        tree.update(
            probe.root(
                emptyList(),
                modifier =
                    Modifier.Empty
                        .size(10, 10)
                        .initialFocus()
                        .onFocusChanged(transitions::add)
                        .onKeyPress { throw primary }
                        .then(FocusAcceptanceElement(true, ArrayList(), editable = true)),
            ),
        )
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        assertNotNull(retainedFocusOwner(tree))
        assertNotNull(tree.currentTextInputFocus())

        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0))
            }

        assertSame(primary, thrown)
        assertEquals(listOf(cleanup), thrown.suppressed.toList())
        assertNull(retainedFocusOwner(tree))
        assertTrue(retainedFocusTargets(tree).isEmpty())
        assertNull(retainedTextInputFocus(tree))
        assertTrue(retainedTextInputTargets(tree).isEmpty())
        assertEquals(listOf(FocusEvent.Gained), transitions)
        tree.close()
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

    private fun retainedFocusOwner(tree: UiTree): Any? {
        val pipeline = reflectedField(tree, "pipeline")
        val focusedInputPipeline = reflectedField(checkNotNull(pipeline), "focusedInputPipeline")
        return reflectedField(checkNotNull(focusedInputPipeline), "focusedOwner")
    }

    private fun retainedFocusTargets(tree: UiTree): List<*> {
        val pipeline = reflectedField(tree, "pipeline")
        val focusedInputPipeline = reflectedField(checkNotNull(pipeline), "focusedInputPipeline")
        return reflectedField(checkNotNull(focusedInputPipeline), "focusedTargets") as List<*>
    }

    private fun retainedTextInputFocus(tree: UiTree): Any? {
        val pipeline = checkNotNull(reflectedField(tree, "pipeline"))
        return reflectedField(checkNotNull(reflectedField(pipeline, "focusedInputPipeline")), "textInputFocus")
    }

    private fun retainedTextInputTargets(tree: UiTree): List<*> {
        val pipeline = checkNotNull(reflectedField(tree, "pipeline"))
        return reflectedField(checkNotNull(reflectedField(pipeline, "focusedInputPipeline")), "textInputTargets") as List<*>
    }

    private fun reflectedField(
        owner: Any,
        name: String,
    ): Any? {
        val field = owner.javaClass.getDeclaredField(name)
        check(field.trySetAccessible()) { "Test inspection could not access $name." }
        return field.get(owner)
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

    private data class FocusAcceptanceElement(
        val accepts: Boolean,
        val transitions: MutableList<FocusEvent>,
        val editable: Boolean = false,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE

        private companion object {
            val TYPE =
                ModifierNodeType(
                    elementClass = FocusAcceptanceElement::class,
                    nodeClass = FocusAcceptanceNode::class,
                    validateLocal = { _ -> },
                    createNode = { element -> FocusAcceptanceNode(element.accepts, element.transitions, element.editable) },
                    updateNode = { _, current, node ->
                        node.acceptsFocus = current.accepts
                        node.requiresTextInput = current.editable
                        DirtyMask.of(DirtyPhase.Paint)
                    },
                )
        }
    }

    private class FocusAcceptanceNode(
        override var acceptsFocus: Boolean,
        private val transitions: MutableList<FocusEvent>,
        override var requiresTextInput: Boolean,
    ) : ModifierNode(),
        FocusTargetNode,
        LifecycleNode {
        private var disposed = false

        override fun onFocusChanged(focused: Boolean) {
            check(disposed.not()) { "Disposed focus targets cannot receive transitions." }
            transitions += if (focused) FocusEvent.Gained else FocusEvent.Lost
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            disposed = true
        }
    }
}
