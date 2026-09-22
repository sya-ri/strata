package dev.s7a.strata.modifier

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.projection.DeclarationProjection

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
     * @property projection optional fixed-policy remote subscription.
     * @property captureButton fixed policy retained by an active gesture, or null for dynamic local callbacks.
     */
    internal data class Element(
        val onCancel: (PointerButton) -> Unit,
        val callback: (PointerEvent, IntOffset) -> InputResult,
        override val projection: DeclarationProjection<*>? = null,
        val captureButton: PointerButton? = null,
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
        private var captured: Element? = null
        private var heldButton: PointerButton? = null

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            val current = captured ?: element
            if (event is PointerEvent.Release && event.button == heldButton) clearGesture()
            return current?.callback?.invoke(event, localPosition) ?: InputResult.Ignored
        }

        override fun onPointerCaptureAcquired(button: PointerButton) {
            val current = element
            if (current?.captureButton != null) {
                captured = current
                heldButton = button
            }
        }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            val current = captured ?: element
            clearGesture()
            current?.onCancel?.invoke(button)
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            element = null
            clearGesture()
        }

        /**
         * Replaces event callbacks while preserving capture and clean frame phases.
         *
         * @param current immutable incoming description retained until the next update or disposal.
         * @return no dirty phases because input reads live callbacks on the owner thread.
         */
        internal fun update(current: Element): DirtyMask {
            // An in-flight gesture keeps its original filter; callback-only changes remain live.
            if (captured?.captureButton == current.captureButton && captured != null) captured = current
            element = current
            return DirtyMask.None
        }

        private fun clearGesture() {
            captured = null
            heldButton = null
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
