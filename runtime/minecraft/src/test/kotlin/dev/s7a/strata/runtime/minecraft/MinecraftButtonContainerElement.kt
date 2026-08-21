package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Test-only public-contract parent that places every child at the same origin for overlap dispatch coverage.
 */
internal class MinecraftButtonContainerElement private constructor(
    children: List<Element>,
) : Element(
        identity = ElementIdentity.Positional,
        type = TYPE,
        children = children,
        modifier = Modifier.Empty,
    ) {
    /**
     * Retained fixed-size parent that measures and overlaps all direct children.
     */
    internal class Node :
        RetainedNode(),
        MeasureNode,
        LayoutNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in 0 until scope.childCount) {
                scope.measureChild(index, constraints)
            }
            return constraints.constrain(IntSize(150, 20))
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) {
                scope.placeChild(index, IntOffset.Zero)
            }
        }
    }

    /**
     * Owns the private test element type token and construction entry point.
     */
    companion object {
        private val TYPE: ElementType<MinecraftButtonContainerElement, Node> =
            ElementType(
                elementClass = MinecraftButtonContainerElement::class,
                nodeClass = Node::class,
                validateLocal = {},
                createNode = { Node() },
                updateNode = { _, _, _ -> DirtyMask.None },
            )

        /**
         * Creates one overlapping-child test description.
         *
         * @param children direct children placed at the same origin.
         * @return a test-only parent element.
         */
        internal fun create(children: List<Element>): Element = MinecraftButtonContainerElement(children)
    }
}
