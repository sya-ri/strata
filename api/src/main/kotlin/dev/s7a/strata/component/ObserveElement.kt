package dev.s7a.strata.component

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.DynamicChildrenNode
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.StateObserverNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Immutable description of one explicitly observed layout region; runtime ownership begins on attachment.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ObserveElement(
    private val sources: List<StateSource<*>>,
    private val content: ObserveContent,
    modifier: Modifier,
    key: ElementKey<*>?,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        modifier = modifier,
    ) {
    /**
     * Owns only current derived children and values, preserving compatible descendants across updates.
     */
    private class Node(
        initial: ObserveElement,
    ) : RetainedNode(),
        StateObserverNode,
        DynamicChildrenNode,
        MeasureNode,
        LayoutNode,
        LifecycleNode {
        override var observedSources: List<StateSource<*>> = initial.sources
            private set
        private var content: ObserveContent? = initial.content
        private var values: List<Any?>? = null
        private var children: List<Element> = emptyList()
        private var pending = true

        override fun commitObservedValues(values: List<Any?>) {
            if (this.values != values) {
                this.values = values
                pending = true
                invalidate(DirtyMask.of(DirtyPhase.Measure))
            }
        }

        override fun dynamicChildren(): List<Element> {
            if (pending) {
                children = checkNotNull(content).evaluate(checkNotNull(values))
                pending = false
            }
            return children
        }

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = if (scope.childCount == 0) constraints.constrain(IntSize.Zero) else scope.measureChild(0, constraints)

        override fun layout(scope: LayoutScope) {
            if (scope.childCount == 1) scope.placeChild(0, IntOffset.Zero)
        }

        override fun attach() = Unit

        override fun detach() = Unit

        override fun dispose() {
            content = null
            values = null
            children = emptyList()
            observedSources = emptyList()
        }

        /**
         * Installs the final parent-produced callback before this region's deferred evaluation.
         */
        fun update(current: ObserveElement): DirtyMask {
            if (content === current.content) return DirtyMask.None
            content = current.content
            observedSources = current.sources
            pending = true
            return DirtyMask.of(DirtyPhase.Measure)
        }
    }

    private companion object {
        private val TYPE =
            ElementType(
                elementClass = ObserveElement::class,
                nodeClass = Node::class,
                validateLocal = { _ -> },
                createNode = ::Node,
                updateNode = { _, current, node -> node.update(current) },
            )
    }
}
