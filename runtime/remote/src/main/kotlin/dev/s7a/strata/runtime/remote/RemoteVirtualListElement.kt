@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.ScrollStateObserver
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PointerInputNode
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Client viewport over the current remote row window, preserving each received row's retained identity.
 * Local movement is immediate; rows outside the received window arrive in the next validated server patch.
 */
internal class RemoteVirtualListElement(
    val properties: RemoteVirtualLists.Properties,
    val state: ScrollState,
    val context: RemoteElementContext,
) : Element(ElementIdentity.Keyed(context.key), TYPE, context.children, context.modifier) {
    private class Viewport(
        initial: RemoteVirtualListElement,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PointerInputNode,
        ClipChildrenNode,
        LifecycleNode {
        private var retained: RemoteVirtualListElement? = initial
        private val current: RemoteVirtualListElement get() = checkNotNull(retained) { "Remote viewport is disposed." }
        private var observation: ScrollStateObserver? = null

        override fun attach() {
            observation = current.state.observe { invalidate(DirtyMask.of(DirtyPhase.Measure)) }
        }

        override fun detach() {
            val previous = observation
            observation = null
            previous?.close()
        }

        override fun dispose() {
            retained = null
            detach()
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val properties = current.properties
            require(constraints.isSatisfiedBy(properties.size))
            repeat(scope.childCount) { scope.measureChild(it, Constraints.fixed(properties.size.width, properties.rowHeight)) }
            current.state.updateGeometry(properties.size.height, properties.count * properties.rowHeight, checkNotNull(observation))
            return properties.size
        }

        override fun layout(scope: LayoutScope) {
            val properties = current.properties
            val offset =
                current.state.metrics.offset
                    .toInt()
            repeat(scope.childCount) { index ->
                scope.placeChild(index, IntOffset(0, (properties.start + index) * properties.rowHeight - offset))
            }
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            if (event !is PointerEvent.Scroll) return InputResult.Ignored
            val delta = event.deltaY * current.properties.rate.toDouble()
            if (delta.isFinite()) {
                current.state.scrollBy(delta)
                val context = current.context
                context.states.flushEdits(context.actions)
                context.actions.send(current.properties.demand, BuiltinProjection.VirtualList.type, ProjectionValue.Real(delta.coerceIn(-Int.MAX_VALUE.toDouble(), Int.MAX_VALUE.toDouble())))
            }
            return InputResult.Consumed
        }

        fun update(next: RemoteVirtualListElement): DirtyMask {
            if (current.state !== next.state) {
                observation?.close()
                observation = next.state.observe { invalidate(DirtyMask.of(DirtyPhase.Measure)) }
            }
            retained = next
            return DirtyMask.of(DirtyPhase.Measure)
        }
    }

    private companion object {
        val TYPE = ElementType(RemoteVirtualListElement::class, Viewport::class, { _ -> }, { Viewport(it) }, { _, next, node -> node.update(next) })
    }
}
