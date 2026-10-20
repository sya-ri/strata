@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.ScrollStateObserver
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Independently placed scrollbar borrowing the same state as a viewport or native multiline editor.
 */
internal class WebScrollbarElement(
    val state: ScrollState,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPE, modifier = modifier) {
    /**
     * Owns one metrics observation and an optional captured thumb gesture.
     */
    private class Node(
        initial: WebScrollbarElement,
    ) : RetainedNode(),
        MeasureNode,
        PaintNode,
        PointerCaptureNode,
        LifecycleNode {
        private var state: ScrollState? = initial.state
        private var observer: ScrollStateObserver? = null
        private var height = 0
        private var anchor = 0
        private var start = 0.0
        private var captured = false
        private val thumbHeight: Int get() {
            val metrics = checkNotNull(state).metrics
            return if (metrics.contentExtent == 0) height else maxOf(12, (height.toLong() * metrics.viewportExtent / metrics.contentExtent).toInt()).coerceAtMost(height)
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.maxHeight < Int.MAX_VALUE) { "Web Scrollbar requires bounded height." }
            val size = constraints.constrain(IntSize(6, constraints.maxHeight))
            height = size.height
            return size
        }

        override fun paint(scope: PaintScope) {
            if (scope.size.width == 0 || height == 0) return
            scope.fillRectangle(IntRect(0, 0, scope.size.width, height), ArgbColor(0xff333333.toInt()))
            val metrics = checkNotNull(state).metrics
            val top = if (metrics.maximumOffset == 0.0) 0 else ((height - thumbHeight) * metrics.offset / metrics.maximumOffset).toInt()
            scope.fillRectangle(IntRect(0, top, scope.size.width, top + thumbHeight), ArgbColor(0xffaaaaaa.toInt()))
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            when (event) {
                is PointerEvent.Press -> {
                    if (event.button == PointerButton.Primary) {
                        anchor = localPosition.y
                        start = checkNotNull(state).metrics.offset
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Drag -> {
                    if (captured && thumbHeight < height) {
                        val metrics = checkNotNull(state).metrics
                        checkNotNull(state).scrollTo((start + (localPosition.y - anchor) * metrics.maximumOffset / (height - thumbHeight)).coerceAtLeast(0.0), checkNotNull(observer))
                        invalidate(DirtyMask.of(DirtyPhase.Paint))
                    }
                    InputResult.Consumed
                }

                is PointerEvent.Scroll -> {
                    checkNotNull(state).scrollBy(event.deltaY * 9, checkNotNull(observer))
                    invalidate(DirtyMask.of(DirtyPhase.Paint))
                    InputResult.Consumed
                }

                is PointerEvent.Release -> {
                    captured = false
                    InputResult.Ignored
                }

                is PointerEvent.Move -> {
                    InputResult.Ignored
                }
            }

        override fun onPointerCaptureAcquired(button: PointerButton) {
            captured = true
        }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            captured = false
        }

        override fun attach() {
            observer = checkNotNull(state).observe { invalidate(DirtyMask.of(DirtyPhase.Paint)) }
        }

        override fun detach() {
            observer?.close()
            observer = null
            captured = false
        }

        override fun dispose() {
            state = null
        }

        /**
         * Replaces an explicitly changed navigation owner and releases its old observation.
         */
        fun update(current: WebScrollbarElement): DirtyMask {
            if (state === current.state) return DirtyMask.None
            observer?.close()
            state = current.state
            observer = current.state.observe { invalidate(DirtyMask.of(DirtyPhase.Paint)) }
            return DirtyMask.of(DirtyPhase.Paint)
        }
    }

    private companion object {
        val TYPE = ElementType(WebScrollbarElement::class, Node::class, { _ -> }, ::Node, { _, current, node -> node.update(current) })
    }
}
