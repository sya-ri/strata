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
import dev.s7a.strata.node.ContentInvalidation
import dev.s7a.strata.node.ContentKind
import dev.s7a.strata.node.DeferredContentNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.LifecycleNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ParentDataDelegateNode
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
    private val transparent: Boolean = false,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = if (transparent) TRANSPARENT_TYPE else TYPE,
        modifier = modifier,
    ) {
    /**
     * Owns only current derived children and values, preserving compatible descendants across updates.
     */
    private class Node(
        initial: ObserveElement,
    ) : RetainedNode(),
        StateObserverNode,
        DeferredContentNode,
        ParentDataDelegateNode,
        MeasureNode,
        LayoutNode,
        LifecycleNode {
        override var observedSources: List<StateSource<*>> = initial.sources
            private set
        private var content: ObserveContent? = initial.content
        private var values: List<Any?>? = null
        private var children: List<Element> = emptyList()
        override val pendingContentReasons: MutableSet<ContentInvalidation> = mutableSetOf(ContentInvalidation.Initial)
        override val parentDataChild: Int? = if (initial.transparent) 0 else null
        override val contentKind: ContentKind = if (initial.transparent) ContentKind.StateComponent else ContentKind.ObservedRegion

        override fun commitObservedValues(values: List<Any?>) {
            if (this.values != values) {
                this.values = values
                pendingContentReasons.add(ContentInvalidation.SourceValue)
            }
        }

        override fun dynamicChildren(): List<Element> {
            if (pendingContentReasons.isNotEmpty()) {
                children = checkNotNull(content).evaluate(checkNotNull(values))
                pendingContentReasons.clear()
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
            pendingContentReasons.clear()
        }

        /**
         * Installs the final parent-produced callback before this region's deferred evaluation.
         */
        fun update(current: ObserveElement): DirtyMask {
            if (content === current.content) return DirtyMask.None
            content = current.content
            if (observedSources.size != current.sources.size || observedSources.indices.any { observedSources[it] !== current.sources[it] }) {
                pendingContentReasons.add(ContentInvalidation.SourceReplacement)
            }
            observedSources = current.sources
            pendingContentReasons.add(ContentInvalidation.ParentDefinition)
            return DirtyMask.None
        }
    }

    private companion object {
        private val TYPE = createType()
        private val TRANSPARENT_TYPE = createType()

        private fun createType() =
            ElementType(
                elementClass = ObserveElement::class,
                nodeClass = Node::class,
                validateLocal = { _ -> },
                createNode = ::Node,
                updateNode = { _, current, node -> node.update(current) },
            )
    }
}
