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
 * Immutable description for horizontal children that wrap into rows under a bounded width.
 *
 * The base [Element] snapshots the direct children without introducing intermediate row parents.
 * Row membership is derived presentation geometry and never changes logical child identity.
 * Checked extent, constraint, and placement failures propagate to the active tree operation.
 *
 * @property horizontalSpacing non-negative fixed gap between children in the same row.
 * @property verticalSpacing non-negative fixed gap between rows.
 * @property horizontalArrangement slack distribution within each row.
 * @property verticalAlignment default child alignment within its measured row height.
 * @param key optional stable identity among direct siblings.
 * @param children direct logical children in declaration order.
 * @param modifier active behavior applied around the retained component.
 * @throws IllegalArgumentException when either spacing value is negative.
 */
internal class FlowRowElement(
    val horizontalSpacing: Int,
    val verticalSpacing: Int,
    val horizontalArrangement: Arrangement,
    val verticalAlignment: VerticalAlignment,
    key: ElementKey<*>?,
    children: List<Element>,
    modifier: Modifier,
) : Element(
        identity = key?.let(ElementIdentity::Keyed) ?: ElementIdentity.Positional,
        type = TYPE,
        children = children,
        modifier = modifier,
    ) {
    init {
        validate(horizontalSpacing, verticalSpacing)
    }

    /**
     * Retained node that measures and places direct children as wrapping horizontal rows.
     *
     * Measurement, placement, and updates run on the owning tree thread.
     * Child sizes and row plans are callback-local values; this node retains only the current layout policies.
     *
     * @param horizontalSpacing initial horizontal gap.
     * @param verticalSpacing initial vertical gap.
     * @param horizontalArrangement initial row arrangement.
     * @param verticalAlignment initial default row-local alignment.
     */
    internal class Node(
        private var horizontalSpacing: Int,
        private var verticalSpacing: Int,
        private var horizontalArrangement: Arrangement,
        private var verticalAlignment: VerticalAlignment,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val childConstraints =
                Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
            val childSizes = List(scope.childCount) { index -> scope.measureChild(index, childConstraints) }
            return constraints.constrain(naturalSize(rows(childSizes, constraints.maxWidth)))
        }

        override fun layout(scope: LayoutScope) {
            val childSizes = List(scope.childCount) { index -> scope.measuredChildSize(index) }
            // The resolved width contains every measured row and cannot exceed the original wrap limit.
            // Repeating the greedy partition at that width therefore preserves the measured row boundaries.
            val rows = rows(childSizes, scope.size.width)
            var top = 0L
            for (index in rows.indices) {
                val row = rows[index]
                placeRow(scope, row, childSizes, Math.toIntExact(top))
                top = Math.addExact(top, row.height.toLong())
                if (index < rows.lastIndex) {
                    top = Math.addExact(top, verticalSpacing.toLong())
                }
            }
        }

        /**
         * Applies changed immutable policies on the owning tree thread.
         *
         * @param previous the previously retained description.
         * @param current the incoming description.
         * @return measurement invalidation for gaps or layout invalidation for arrangement and default alignment.
         */
        internal fun update(
            previous: FlowRowElement,
            current: FlowRowElement,
        ): DirtyMask {
            var dirty = DirtyMask.None
            if (
                previous.horizontalSpacing != current.horizontalSpacing ||
                previous.verticalSpacing != current.verticalSpacing
            ) {
                dirty += DirtyMask.of(DirtyPhase.Measure)
            } else if (
                previous.horizontalArrangement != current.horizontalArrangement ||
                previous.verticalAlignment != current.verticalAlignment
            ) {
                dirty += DirtyMask.of(DirtyPhase.Layout)
            }
            horizontalSpacing = current.horizontalSpacing
            verticalSpacing = current.verticalSpacing
            horizontalArrangement = current.horizontalArrangement
            verticalAlignment = current.verticalAlignment
            return dirty
        }

        private fun rows(
            childSizes: List<IntSize>,
            maximumWidth: Int,
        ): List<Row> {
            val rows = ArrayList<Row>()
            var start = 0
            var width = 0L
            var height = 0
            for (index in childSizes.indices) {
                val child = childSizes[index]
                val nextWidth =
                    if (index == start) {
                        child.width.toLong()
                    } else {
                        Math.addExact(Math.addExact(width, horizontalSpacing.toLong()), child.width.toLong())
                    }
                if (start < index && maximumWidth != Int.MAX_VALUE && maximumWidth.toLong() < nextWidth) {
                    rows += Row(start, index, Math.toIntExact(width), height)
                    start = index
                    width = child.width.toLong()
                    height = child.height
                } else {
                    width = nextWidth
                    height = maxOf(height, child.height)
                }
            }
            if (start < childSizes.size) {
                rows += Row(start, childSizes.size, Math.toIntExact(width), height)
            }
            return rows
        }

        private fun naturalSize(rows: List<Row>): IntSize {
            var width = 0
            var height = 0L
            for (index in rows.indices) {
                val row = rows[index]
                width = maxOf(width, row.width)
                height = Math.addExact(height, row.height.toLong())
                if (index < rows.lastIndex) {
                    height = Math.addExact(height, verticalSpacing.toLong())
                }
            }
            return IntSize(width, Math.toIntExact(height))
        }

        private fun placeRow(
            scope: LayoutScope,
            row: Row,
            childSizes: List<IntSize>,
            top: Int,
        ) {
            val slack = Math.subtractExact(scope.size.width.toLong(), row.width.toLong()).coerceAtLeast(0L)
            val childCount = row.end - row.start
            var left = 0L
            for (index in row.start until row.end) {
                val child = childSizes[index]
                val extra = horizontalArrangement.offset(slack, index - row.start, childCount)
                val verticalOffset = verticalOffset(scope, index, row.height, child.height)
                scope.placeChild(
                    index,
                    IntOffset(Math.toIntExact(Math.addExact(left, extra)), Math.addExact(top, verticalOffset)),
                )
                left = Math.addExact(left, child.width.toLong())
                if (index < row.end - 1) {
                    left = Math.addExact(left, horizontalSpacing.toLong())
                }
            }
        }

        private fun verticalOffset(
            scope: LayoutScope,
            index: Int,
            rowHeight: Int,
            childHeight: Int,
        ): Int {
            val alignment = scope.childParentData(index, FlowRowAlignmentParentData.KEY)?.alignment ?: verticalAlignment
            val slack = Math.subtractExact(rowHeight, childHeight)
            return when (alignment) {
                VerticalAlignment.Top -> 0
                VerticalAlignment.Center -> slack / 2
                VerticalAlignment.Bottom -> slack
            }
        }

        private data class Row(
            val start: Int,
            val end: Int,
            val width: Int,
            val height: Int,
        )
    }

    /**
     * Stable token and validation boundary for the wrapping horizontal layout.
     */
    companion object {
        internal val TYPE: ElementType<FlowRowElement, Node> =
            ElementType(
                elementClass = FlowRowElement::class,
                nodeClass = Node::class,
                validateLocal = { element -> validate(element.horizontalSpacing, element.verticalSpacing) },
                createNode = { element ->
                    Node(
                        horizontalSpacing = element.horizontalSpacing,
                        verticalSpacing = element.verticalSpacing,
                        horizontalArrangement = element.horizontalArrangement,
                        verticalAlignment = element.verticalAlignment,
                    )
                },
                updateNode = { previous, current, node -> node.update(previous, current) },
            )

        private fun validate(
            horizontalSpacing: Int,
            verticalSpacing: Int,
        ) {
            require(0 <= horizontalSpacing) { "FlowRow horizontal spacing must be non-negative." }
            require(0 <= verticalSpacing) { "FlowRow vertical spacing must be non-negative." }
        }
    }
}
