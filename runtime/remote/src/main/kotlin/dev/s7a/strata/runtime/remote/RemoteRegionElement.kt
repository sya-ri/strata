@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ParentDataDelegateNode
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Client counterpart of a server-evaluated observed region, retaining parent-data delegation.
 */
internal class RemoteRegionElement(
    context: RemoteElementContext,
    private val transparent: Boolean,
) : Element(
        ElementIdentity.Keyed(context.key),
        if (transparent) TRANSPARENT else OPAQUE,
        context.children,
        context.modifier,
    ) {
    init {
        require(children.size <= 1) { "An observed remote region has at most one child." }
    }

    /**
     * Pass-through layout retaining the same logical scope as the server-side observed region.
     */
    private class Node(
        transparent: Boolean,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode,
        ParentDataDelegateNode {
        override val parentDataChild: Int? = if (transparent) 0 else null

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = if (scope.childCount == 0) constraints.constrain(IntSize.Zero) else scope.measureChild(0, constraints)

        override fun layout(scope: LayoutScope) {
            if (scope.childCount == 1) scope.placeChild(0, IntOffset.Zero)
        }
    }

    private companion object {
        private val TRANSPARENT = type()
        private val OPAQUE = type()

        private fun type(): ElementType<RemoteRegionElement, Node> =
            ElementType(
                RemoteRegionElement::class,
                Node::class,
                { _ -> },
                { Node(it.transparent) },
                { _, _, _ -> DirtyMask.None },
            )
    }
}
