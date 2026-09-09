package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource

/**
 * Immutable activation description whose source subscription belongs to the retained tree registry.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObservedActivationModifier(
    private val enabled: StateSource<Boolean>,
    private val action: () -> Unit,
) : ModifierElement {
    override val type: ModifierNodeType<*, *>
        get() = TYPE

    /**
     * Keeps focus eligibility and action delivery consistent with the last committed source value.
     */
    private class Node(
        initial: ObservedActivationModifier,
    ) : ModifierNode(),
        StateObserverNode,
        PointerInputNode,
        KeyboardInputNode,
        FocusTargetNode,
        LifecycleNode {
        override var observedSources: List<StateSource<*>> = listOf(initial.enabled)
            private set
        private var enabled = false
        private var action: (() -> Unit)? = initial.action

        override val acceptsFocus: Boolean
            get() = enabled

        override fun commitObservedValues(values: List<Any?>) {
            val next = values.single() as Boolean
            if (enabled != next) {
                enabled = next
                invalidate(DirtyMask.of(DirtyPhase.Layout))
            }
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult = if (enabled && event is PointerEvent.Press && event.button == PointerButton.Primary) activate() else InputResult.Ignored

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult {
            if (enabled.not() || event !is KeyboardEvent.Press) return InputResult.Ignored
            return when (event.key) {
                KeyCode.Enter, KeyCode.Space -> activate()
                else -> InputResult.Ignored
            }
        }

        override fun onFocusChanged(focused: Boolean) = Unit

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            action = null
            enabled = false
            observedSources = emptyList()
        }

        /**
         * Replaces the callback without invalidation; source identity changes request focus reconciliation.
         */
        fun update(current: ObservedActivationModifier): DirtyMask {
            action = current.action
            if (observedSources.single() === current.enabled) return DirtyMask.None
            observedSources = listOf(current.enabled)
            return DirtyMask.of(DirtyPhase.Layout)
        }

        private fun activate(): InputResult {
            checkNotNull(action).invoke()
            return InputResult.Consumed
        }
    }

    private companion object {
        private val TYPE =
            ModifierNodeType(
                elementClass = ObservedActivationModifier::class,
                nodeClass = Node::class,
                validateLocal = { _ -> },
                createNode = ::Node,
                updateNode = { _, current, node -> node.update(current) },
            )
    }
}
