package dev.s7a.strata.layout

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description for an overlay stack.
 *
 * The base [Element] snapshots the direct children and retains the immutable modifier chain.
 * After submission, the runtime owns this description snapshot and the retained [Node] created from it.
 * Checked extent and placement arithmetic failures propagate to the active tree operation.
 *
 * @property contentAlignment the default two-axis placement policy for direct children.
 * @param key optional stable identity among direct siblings.
 * @param children direct logical children in declaration order.
 * @param modifier active behavior applied around the retained component.
 */
internal class StackElement(
    val contentAlignment: Alignment,
    key: ElementKey<*>?,
    children: List<Element>,
    modifier: Modifier,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = children,
        modifier = modifier,
    ) {
    /**
     * Retained node that measures every child and places each child in the stack.
     *
     * @param contentAlignment the initial default child placement policy.
     */
    internal class Node(
        private var contentAlignment: Alignment,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            var naturalWidth = 0
            var naturalHeight = 0
            val childConstraints =
                Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
            for (index in 0 until scope.childCount) {
                val childSize = scope.measureChild(index, childConstraints)
                if (naturalWidth < childSize.width) {
                    naturalWidth = childSize.width
                }
                if (naturalHeight < childSize.height) {
                    naturalHeight = childSize.height
                }
            }
            return constraints.constrain(IntSize(naturalWidth, naturalHeight))
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) {
                val childSize = scope.measuredChildSize(index)
                val alignment = scope.childParentData(index, StackAlignmentParentData.KEY)?.alignment ?: contentAlignment
                val horizontalDifference = Math.subtractExact(scope.size.width, childSize.width)
                val verticalDifference = Math.subtractExact(scope.size.height, childSize.height)
                val x =
                    when (alignment.horizontalAlignment) {
                        HorizontalAlignment.Start -> 0
                        HorizontalAlignment.Center -> horizontalDifference / 2
                        HorizontalAlignment.End -> horizontalDifference
                    }
                val y =
                    when (alignment.verticalAlignment) {
                        VerticalAlignment.Top -> 0
                        VerticalAlignment.Center -> verticalDifference / 2
                        VerticalAlignment.Bottom -> verticalDifference
                    }
                scope.placeChild(index, IntOffset(x, y))
            }
        }

        /**
         * Applies a changed immutable description to this retained node.
         *
         * @param previous the previously retained description.
         * @param current the incoming description.
         * @return layout invalidation when the default alignment changed.
         */
        internal fun update(
            previous: StackElement,
            current: StackElement,
        ): DirtyMask {
            val changed = previous.contentAlignment != current.contentAlignment
            contentAlignment = current.contentAlignment
            return if (changed) DirtyMask.of(DirtyPhase.Layout) else DirtyMask.None
        }
    }

    /**
     * Stable token for the stack component.
     */
    companion object {
        internal val TYPE: ElementType<StackElement, Node> =
            ElementType(
                elementClass = StackElement::class,
                nodeClass = Node::class,
                validateLocal = { _ -> },
                createNode = { element -> Node(element.contentAlignment) },
                updateNode = { previous, current, node -> node.update(previous, current) },
            )
    }
}
