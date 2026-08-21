package dev.s7a.strata.modifier

import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.TextInputEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.FocusTargetNode
import dev.s7a.strata.node.KeyboardInputNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.TextInputNode

/**
 * Internal implementation shared by focused keyboard, text-input, preedit, and focus modifiers.
 *
 * Descriptions retain caller callbacks until replacement or permanent disposal.
 * Runtime delivery is synchronous on the owning tree thread, and escaping failures poison retained ownership through the active input operation.
 */
internal object FocusedInputModifier {
    /**
     * Immutable focused-input action description.
     *
     * @property action typed callback or focus behavior retained by the modifier node.
     */
    internal data class Element(
        val action: Action,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Typed focused-input behavior handled by one modifier node.
     */
    internal sealed interface Action {
        /**
         * @property callback every-key callback.
         */
        data class EveryKey(
            val callback: (KeyboardEvent) -> InputResult,
        ) : Action

        /**
         * @property callback key-press callback.
         */
        data class KeyPress(
            val callback: (KeyboardEvent.Press) -> InputResult,
        ) : Action

        /**
         * @property callback key-release callback.
         */
        data class KeyRelease(
            val callback: (KeyboardEvent.Release) -> InputResult,
        ) : Action

        /**
         * @property callback every text-input callback.
         */
        data class EveryText(
            val callback: (TextInputEvent) -> InputResult,
        ) : Action

        /**
         * @property callback committed-character callback.
         */
        data class Character(
            val callback: (TextInputEvent.Character) -> InputResult,
        ) : Action

        /**
         * @property callback preedit callback.
         */
        data class Preedit(
            val callback: (TextInputEvent.Preedit) -> InputResult,
        ) : Action

        /**
         * @property callback distinct focus-transition callback.
         */
        data class FocusChange(
            val callback: (FocusEvent) -> Unit,
        ) : Action

        /**
         * Focus target without a callback.
         */
        data object Focusable : Action

        /**
         * Focus target selected when layout has no retained focus owner.
         */
        data object InitialFocus : Action

        /**
         * Released behavior retained only after permanent disposal.
         */
        data object Released : Action
    }

    /**
     * Retained focused-input callback and focus-target behavior.
     *
     * @param initialAction initial typed behavior.
     */
    internal class Node(
        initialAction: Action,
    ) : ModifierNode(),
        KeyboardInputNode,
        TextInputNode,
        FocusTargetNode,
        LifecycleNode {
        private var action: Action = initialAction
        private var focused = false

        override val acceptsFocus: Boolean
            get() = action !== Action.Released

        override val requestsInitialFocus: Boolean
            get() = action === Action.InitialFocus

        override fun onKeyboardEvent(event: KeyboardEvent): InputResult =
            when (val current = action) {
                is Action.EveryKey -> current.callback(event)

                is Action.KeyPress -> if (event is KeyboardEvent.Press) current.callback(event) else InputResult.Ignored

                is Action.KeyRelease -> if (event is KeyboardEvent.Release) current.callback(event) else InputResult.Ignored

                is Action.Character,
                is Action.EveryText,
                is Action.FocusChange,
                Action.Focusable,
                Action.InitialFocus,
                is Action.Preedit,
                Action.Released,
                -> InputResult.Ignored
            }

        override fun onTextInput(event: TextInputEvent): InputResult =
            when (val current = action) {
                is Action.EveryText -> current.callback(event)

                is Action.Character -> if (event is TextInputEvent.Character) current.callback(event) else InputResult.Ignored

                is Action.Preedit -> if (event is TextInputEvent.Preedit) current.callback(event) else InputResult.Ignored

                is Action.EveryKey,
                is Action.FocusChange,
                is Action.KeyPress,
                is Action.KeyRelease,
                Action.Focusable,
                Action.InitialFocus,
                Action.Released,
                -> InputResult.Ignored
            }

        override fun onFocusChanged(focused: Boolean) {
            if (this.focused == focused) return
            this.focused = focused
            val current = action
            if (current is Action.FocusChange) {
                current.callback(if (focused) FocusEvent.Gained else FocusEvent.Lost)
            }
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            action = Action.Released
            focused = false
        }

        /**
         * Replaces callback behavior without invalidating declarative frame phases.
         *
         * @param element incoming immutable action description.
         * @return no frame phase because focused input reads the current action during dispatch.
         */
        internal fun update(element: Element): DirtyMask {
            action = element.action
            return DirtyMask.None
        }
    }

    /**
     * Stable token shared by every focused-input modifier description.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element.action) },
            updateNode = { _, current, node -> node.update(current) },
        )
}
