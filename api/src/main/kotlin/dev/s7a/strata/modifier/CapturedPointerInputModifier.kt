package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PointerCaptureNode

/**
 * Owns the stable retained token for captured pointer handlers.
 *
 * Descriptions retain caller callbacks until replacement or permanent node disposal.
 * Every update and invocation runs synchronously on the owning tree thread, and callback failures propagate unchanged to that tree.
 */
internal object CapturedPointerInputModifier {
    /**
     * Immutable callback description for one independently captured modifier entry.
     *
     * @property onCancel owner-thread cancellation callback retained until replacement or disposal.
     * @property callback owner-thread event callback receiving current local logical coordinates.
     */
    internal data class Element(
        val onCancel: (PointerButton) -> Unit,
        val callback: (PointerEvent, IntOffset) -> InputResult,
    ) : ModifierElement {
        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retains callbacks without owning the runtime's capture state.
     *
     * The owning tree clears capture before cancellation and before disposing this entry.
     * Updates preserve entry identity, so replacing either callback does not interrupt an active gesture.
     *
     * @param initial initial immutable callbacks, retained on the tree owner thread.
     */
    internal class Node(
        initial: Element,
    ) : ModifierNode(),
        PointerCaptureNode,
        LifecycleNode {
        private var element: Element? = initial

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult = element?.callback?.invoke(event, localPosition) ?: InputResult.Ignored

        override fun onPointerCaptureCancelled(button: PointerButton) {
            element?.onCancel?.invoke(button)
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            element = null
        }

        /**
         * Replaces event callbacks while preserving capture and clean frame phases.
         *
         * @param current immutable incoming description retained until the next update or disposal.
         * @return no dirty phases because input reads live callbacks on the owner thread.
         */
        internal fun update(current: Element): DirtyMask {
            element = current
            return DirtyMask.None
        }
    }

    /**
     * Referential token shared only by captured pointer modifiers.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { _ -> },
            createNode = { element -> Node(element) },
            updateNode = { _, current, node -> node.update(current) },
        )
}
