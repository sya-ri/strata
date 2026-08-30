package dev.s7a.strata.modifier

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.PointerCaptureNode
import kotlin.math.exp
import kotlin.math.ln

/**
 * Owns retained pointer gesture state for [panZoom].
 *
 * Description values retain the caller-owned [PanZoomState] without acquiring it.
 * The retained node uses the generic pointer-capture contract and mutates state synchronously on the tree owner thread.
 */
internal object PanZoomModifier {
    /**
     * Immutable input policy for one pan-and-zoom modifier entry.
     *
     * @property state caller-owned transform mutated by accepted gestures.
     * @property panButton typed button that begins a captured pan.
     * @property zoomStep finite factor applied for one logical scroll unit.
     */
    internal data class Element(
        val state: PanZoomState,
        val panButton: PointerButton,
        val zoomStep: Double,
    ) : ModifierElement {
        init {
            validateZoomStep(zoomStep)
        }

        override val type: ModifierNodeType<*, *>
            get() = TYPE
    }

    /**
     * Retains whether this entry began the currently captured pan gesture.
     *
     * Press, drag, release, cancellation, and updates are serialized by the owning tree thread.
     * The node owns no rendering resources and invalidates no phases because observers of [PanZoomState] own presentation invalidation.
     *
     * @param initial initial immutable state and input policy.
     */
    internal class Node(
        initial: Element,
    ) : ModifierNode(),
        PointerCaptureNode {
        private var element: Element = initial
        private var capturedButton: PointerButton? = null

        override fun onPointerCaptureAcquired(button: PointerButton) {
            check(button == element.panButton && capturedButton == null) { "Pan-and-zoom capture was acquired for an unexpected gesture." }
            capturedButton = button
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            when (event) {
                is PointerEvent.Press -> beginPan(event)
                is PointerEvent.Drag -> continuePan(event)
                is PointerEvent.Release -> endPan(event)
                is PointerEvent.Scroll -> zoom(event, localPosition)
                is PointerEvent.Move -> InputResult.Ignored
            }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            if (capturedButton == button) capturedButton = null
        }

        /**
         * Replaces state and input policy without interrupting a capture already owned by this retained entry.
         *
         * @param current incoming immutable description.
         * @return no dirty phases because this modifier only handles input against live state.
         */
        internal fun update(current: Element): DirtyMask {
            element = current
            return DirtyMask.None
        }

        private fun beginPan(event: PointerEvent.Press): InputResult =
            if (event.button == element.panButton && capturedButton == null) {
                InputResult.Consumed
            } else {
                InputResult.Ignored
            }

        private fun continuePan(event: PointerEvent.Drag): InputResult {
            if (capturedButton != event.button) return InputResult.Ignored
            val metrics = element.state.metrics
            val scale = if (metrics.geometryKnown) metrics.scale else 1.0
            element.state.panBy(DoubleOffset(-event.deltaX / scale, -event.deltaY / scale))
            return InputResult.Consumed
        }

        private fun endPan(event: PointerEvent.Release): InputResult {
            if (capturedButton != event.button) return InputResult.Ignored
            capturedButton = null
            return InputResult.Consumed
        }

        private fun zoom(
            event: PointerEvent.Scroll,
            localPosition: IntOffset,
        ): InputResult {
            if (event.deltaY == 0.0) return InputResult.Ignored
            val state = element.state
            val current = state.metrics.zoom
            val minimumLog = ln(state.minimumZoom)
            val maximumLog = ln(state.maximumZoom)
            val requestedLog = ln(current) - event.deltaY * ln(element.zoomStep)
            val requested =
                when {
                    requestedLog <= minimumLog -> state.minimumZoom
                    maximumLog <= requestedLog -> state.maximumZoom
                    else -> exp(requestedLog)
                }
            val anchor =
                if (state.metrics.geometryKnown) {
                    DoubleOffset(localPosition.x.toDouble(), localPosition.y.toDouble())
                } else {
                    null
                }
            state.zoomTo(requested, anchor)
            return InputResult.Consumed
        }
    }

    /**
     * Referential token shared only by pan-and-zoom modifiers.
     */
    internal val TYPE: ModifierNodeType<Element, Node> =
        ModifierNodeType(
            elementClass = Element::class,
            nodeClass = Node::class,
            validateLocal = { element -> validateZoomStep(element.zoomStep) },
            createNode = { element -> Node(element) },
            updateNode = { _, current, node -> node.update(current) },
        )

    private fun validateZoomStep(value: Double) {
        require(value.isFinite() && 1.0 < value) { "Pan-and-zoom scroll step must be finite and greater than one." }
    }
}
