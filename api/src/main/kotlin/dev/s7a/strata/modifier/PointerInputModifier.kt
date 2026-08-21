package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PointerHoverNode
import dev.s7a.strata.node.PointerInputNode

/**
 * Internal implementation of the general pointer-action modifier family.
 *
 * Descriptions retain caller callbacks until replacement or permanent modifier-node disposal.
 * Runtime invokes callbacks synchronously on the owning tree thread, and an escaping failure poisons retained ownership through the active tree operation.
 */
internal object PointerInputModifier {
    /**
     * Immutable pointer-action description.
     *
     * @property action typed callback behavior retained by the modifier node.
     */
    internal data class Element(
        val action: Action,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Typed callback variant handled by one modifier node.
     */
    internal sealed interface Action {
        /**
         * Handles every pointer-event variant.
         *
         * @property callback callback receiving the tree event and modifier-local position.
         */
        data class Every(
            val callback: (PointerEvent, IntOffset) -> InputResult,
        ) : Action

        /**
         * Handles press events.
         *
         * @property callback callback receiving the typed event and modifier-local position.
         */
        data class Press(
            val callback: (PointerEvent.Press, IntOffset) -> InputResult,
        ) : Action

        /**
         * Handles release events.
         *
         * @property callback callback receiving the typed event and modifier-local position.
         */
        data class Release(
            val callback: (PointerEvent.Release, IntOffset) -> InputResult,
        ) : Action

        /**
         * Handles move events that hit the modifier bounds.
         *
         * @property callback callback receiving the typed event and modifier-local position.
         */
        data class Move(
            val callback: (PointerEvent.Move, IntOffset) -> InputResult,
        ) : Action

        /**
         * Handles drag events that hit the modifier bounds.
         *
         * @property callback callback receiving the typed event and modifier-local position.
         */
        data class Drag(
            val callback: (PointerEvent.Drag, IntOffset) -> InputResult,
        ) : Action

        /**
         * Handles scroll events.
         *
         * @property callback callback receiving the typed event and modifier-local position.
         */
        data class Scroll(
            val callback: (PointerEvent.Scroll, IntOffset) -> InputResult,
        ) : Action

        /**
         * Handles hit-region hover transitions without consuming move events.
         *
         * @property callback callback receiving only distinct enter and exit transitions.
         */
        data class Hover(
            val callback: (PointerHoverEvent) -> Unit,
        ) : Action

        /**
         * Released action retained after permanent disposal.
         */
        data object Released : Action
    }

    /**
     * Retained pointer callback and hover-transition behavior.
     *
     * @param initialAction initial typed callback behavior.
     * @throws Throwable when an active caller callback fails during pointer or hover delivery.
     */
    internal class Node(
        initialAction: Action,
    ) : ModifierNode(),
        PointerInputNode,
        PointerHoverNode,
        LifecycleNode {
        private var action: Action = initialAction
        private var hovered: Boolean = false

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            when (val current = action) {
                is Action.Every -> current.callback(event, localPosition)
                is Action.Press -> if (event is PointerEvent.Press) current.callback(event, localPosition) else InputResult.Ignored
                is Action.Release -> if (event is PointerEvent.Release) current.callback(event, localPosition) else InputResult.Ignored
                is Action.Move -> if (event is PointerEvent.Move) current.callback(event, localPosition) else InputResult.Ignored
                is Action.Drag -> if (event is PointerEvent.Drag) current.callback(event, localPosition) else InputResult.Ignored
                is Action.Scroll -> if (event is PointerEvent.Scroll) current.callback(event, localPosition) else InputResult.Ignored
                is Action.Hover, Action.Released -> InputResult.Ignored
            }

        override fun onPointerHover(hovered: Boolean) {
            when (val current = action) {
                is Action.Hover -> {
                    if (this.hovered != hovered) {
                        this.hovered = hovered
                        current.callback(if (hovered) PointerHoverEvent.Enter else PointerHoverEvent.Exit)
                    }
                }

                else -> {
                    this.hovered = false
                }
            }
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            action = Action.Released
            hovered = false
        }

        /**
         * Replaces the callback behavior without invalidating declarative frame phases.
         *
         * Callback-only changes affect subsequent input and reset stale hover state when the action kind changes.
         *
         * @param element incoming immutable action description.
         * @return no frame phase because pointer behavior is read directly during dispatch.
         */
        internal fun update(element: Element): DirtyMask {
            if ((action is Action.Hover) != (element.action is Action.Hover)) {
                hovered = false
            }
            action = element.action
            return DirtyMask.None
        }
    }

    /**
     * Stable token shared by every pointer-action modifier description.
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
