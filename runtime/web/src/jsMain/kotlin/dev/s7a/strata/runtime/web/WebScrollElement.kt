@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.DragScrollOptions
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.ScrollStateObserver
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerGesture
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.DragScrollNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Clipped retained scroll viewport with the standard two-pixel content inset.
 */
internal class WebScrollElement(
    val state: ScrollState,
    val rate: Int,
    content: Element,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional, TYPE, listOf(content), modifier) {
    /**
     * Owns one scroll observation and delegates content-drag recognition to the shared runtime.
     */
    private class Node(
        initial: WebScrollElement,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        ClipChildrenNode,
        DragScrollNode,
        LifecycleNode {
        private var state: ScrollState? = initial.state
        private var observer: ScrollStateObserver? = null
        private var rate = initial.rate

        override fun onPointerGesture(
            gesture: PointerGesture,
            localPosition: IntOffset,
        ): InputResult =
            if (gesture is PointerGesture.Pan && dragScrollOptions is DragScrollOptions.Enabled) {
                move(-gesture.deltaY)
                InputResult.Consumed
            } else {
                InputResult.Ignored
            }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(constraints.maxWidth < Int.MAX_VALUE && constraints.maxHeight < Int.MAX_VALUE) { "Web ScrollArea requires bounded dimensions." }
            val size = IntSize(constraints.maxWidth, constraints.maxHeight)
            val child = scope.measureChild(0, Constraints(maxWidth = size.width))
            checkNotNull(state).updateGeometry(size.height, child.height + 4, checkNotNull(observer))
            return size
        }

        override fun layout(scope: LayoutScope) {
            val size = scope.measuredChildSize(0)
            scope.placeChild(0, IntOffset((scope.size.width - size.width) / 2, 2 - checkNotNull(state).metrics.offset.toInt()))
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult =
            when (event) {
                is PointerEvent.Scroll -> {
                    move(event.deltaY * rate)
                    InputResult.Consumed
                }

                is PointerEvent.Drag -> {
                    if (event.button == PointerButton.Primary && dragScrollOptions is DragScrollOptions.Enabled) {
                        move(-event.deltaY)
                        InputResult.Consumed
                    } else {
                        InputResult.Ignored
                    }
                }

                is PointerEvent.Press,
                is PointerEvent.Release,
                is PointerEvent.Move,
                -> {
                    InputResult.Ignored
                }
            }

        override val dragScrollOptions: DragScrollOptions
            get() = checkNotNull(state).dragScroll

        override val dragScrollIdentity: Any
            get() = checkNotNull(state)

        override val canDragScroll: Boolean
            get() = checkNotNull(state).metrics.canScroll

        override fun scrollByDrag(delta: Double): Double {
            val previous = checkNotNull(state).metrics.offset
            move(delta)
            return checkNotNull(state).metrics.offset - previous
        }

        override fun attach() {
            observer = checkNotNull(state).observe { invalidate(DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint)) }
        }

        override fun detach() {
            observer?.close()
            observer = null
        }

        override fun dispose() {
            state = null
        }

        /**
         * Reuses offset and observer unless the caller explicitly replaces the state owner.
         */
        fun update(element: WebScrollElement): DirtyMask {
            if (state !== element.state) {
                observer?.close()
                state = element.state
                attach()
            }
            rate = element.rate
            return DirtyMask.of(DirtyPhase.Measure, DirtyPhase.Layout)
        }

        private fun move(delta: Double) {
            checkNotNull(state).scrollBy(delta, checkNotNull(observer))
            invalidate(DirtyMask.of(DirtyPhase.Layout, DirtyPhase.Paint))
        }
    }

    private companion object {
        val TYPE = ElementType(WebScrollElement::class, Node::class, { _ -> }, ::Node, { _, current, node -> node.update(current) })
    }
}
