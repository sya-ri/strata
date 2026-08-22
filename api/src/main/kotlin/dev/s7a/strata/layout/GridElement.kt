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
 * Internal immutable description for a fixed-column grid.
 *
 * Children occupy cells in row-major order.
 * Each column and row takes the greatest measured extent of the children assigned to that track.
 * Checked extent and placement arithmetic failures propagate to the active tree operation.
 *
 * @property columns positive number of columns.
 * @property horizontalSpacing non-negative spacing between adjacent columns.
 * @property verticalSpacing non-negative spacing between adjacent rows.
 * @property contentAlignment default placement of each child inside its measured cell.
 * @param key optional stable identity among direct siblings.
 * @param children direct logical children in declaration order.
 * @param modifier active behavior applied around the retained component.
 */
internal class GridElement(
    val columns: Int,
    val horizontalSpacing: Int,
    val verticalSpacing: Int,
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
    init {
        validate(columns, horizontalSpacing, verticalSpacing)
    }

    /**
     * Retained node that measures and places one fixed-column grid.
     *
     * @param columns initial positive number of columns.
     * @param horizontalSpacing initial horizontal track spacing.
     * @param verticalSpacing initial vertical track spacing.
     * @param contentAlignment initial default cell alignment.
     */
    internal class Node(
        private var columns: Int,
        private var horizontalSpacing: Int,
        private var verticalSpacing: Int,
        private var contentAlignment: Alignment,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            val columnWidths = IntArray(minOf(columns, scope.childCount))
            val rowHeights = IntArray(rowCount(scope.childCount, columns))
            val childConstraints =
                Constraints(
                    minWidth = 0,
                    maxWidth = constraints.maxWidth,
                    minHeight = 0,
                    maxHeight = constraints.maxHeight,
                )
            for (index in 0 until scope.childCount) {
                val childSize = scope.measureChild(index, childConstraints)
                recordTrackExtents(index, childSize, columnWidths, rowHeights)
            }
            return constraints.constrain(naturalSize(columnWidths, rowHeights))
        }

        override fun layout(scope: LayoutScope) {
            val columnWidths = IntArray(minOf(columns, scope.childCount))
            val rowHeights = IntArray(rowCount(scope.childCount, columns))
            for (index in 0 until scope.childCount) {
                recordTrackExtents(index, scope.measuredChildSize(index), columnWidths, rowHeights)
            }
            val columnOffsets = trackOffsets(columnWidths, horizontalSpacing)
            val rowOffsets = trackOffsets(rowHeights, verticalSpacing)
            for (index in 0 until scope.childCount) {
                val column = index % columns
                val row = index / columns
                val childSize = scope.measuredChildSize(index)
                val alignment = scope.childParentData(index, GridAlignmentParentData.KEY)?.alignment ?: contentAlignment
                val horizontalSlack = Math.subtractExact(columnWidths[column], childSize.width)
                val verticalSlack = Math.subtractExact(rowHeights[row], childSize.height)
                val cellOffset = alignmentOffset(alignment, horizontalSlack, verticalSlack)
                scope.placeChild(
                    index,
                    IntOffset(
                        Math.addExact(columnOffsets[column], cellOffset.x),
                        Math.addExact(rowOffsets[row], cellOffset.y),
                    ),
                )
            }
        }

        /**
         * Applies a changed immutable description to this retained node.
         *
         * @param previous the previously retained description.
         * @param current the incoming description.
         * @return measurement or layout invalidation for the changed grid properties.
         */
        internal fun update(
            previous: GridElement,
            current: GridElement,
        ): DirtyMask {
            var dirty = DirtyMask.None
            if (
                previous.columns != current.columns ||
                previous.horizontalSpacing != current.horizontalSpacing ||
                previous.verticalSpacing != current.verticalSpacing
            ) {
                dirty += DirtyMask.of(DirtyPhase.Measure)
            } else if (previous.contentAlignment != current.contentAlignment) {
                dirty += DirtyMask.of(DirtyPhase.Layout)
            }
            columns = current.columns
            horizontalSpacing = current.horizontalSpacing
            verticalSpacing = current.verticalSpacing
            contentAlignment = current.contentAlignment
            return dirty
        }

        private fun recordTrackExtents(
            index: Int,
            size: IntSize,
            columnWidths: IntArray,
            rowHeights: IntArray,
        ) {
            val column = index % columns
            val row = index / columns
            if (columnWidths[column] < size.width) {
                columnWidths[column] = size.width
            }
            if (rowHeights[row] < size.height) {
                rowHeights[row] = size.height
            }
        }

        private fun naturalSize(
            columnWidths: IntArray,
            rowHeights: IntArray,
        ): IntSize =
            IntSize(
                checkedExtent(columnWidths, horizontalSpacing),
                checkedExtent(rowHeights, verticalSpacing),
            )

        private fun checkedExtent(
            tracks: IntArray,
            spacing: Int,
        ): Int {
            var extent = 0L
            for (track in tracks) {
                extent = Math.addExact(extent, track.toLong())
            }
            val gapCount = if (tracks.isEmpty()) 0 else tracks.size - 1
            extent = Math.addExact(extent, Math.multiplyExact(spacing.toLong(), gapCount.toLong()))
            return Math.toIntExact(extent)
        }

        private fun trackOffsets(
            tracks: IntArray,
            spacing: Int,
        ): IntArray {
            val offsets = IntArray(tracks.size)
            var offset = 0L
            for (index in tracks.indices) {
                offsets[index] = Math.toIntExact(offset)
                offset = Math.addExact(offset, tracks[index].toLong())
                if (index < tracks.lastIndex) {
                    offset = Math.addExact(offset, spacing.toLong())
                }
            }
            return offsets
        }

        private fun alignmentOffset(
            alignment: Alignment,
            horizontalSlack: Int,
            verticalSlack: Int,
        ): IntOffset {
            val x =
                when (alignment.horizontalAlignment) {
                    HorizontalAlignment.Start -> 0
                    HorizontalAlignment.Center -> horizontalSlack / 2
                    HorizontalAlignment.End -> horizontalSlack
                }
            val y =
                when (alignment.verticalAlignment) {
                    VerticalAlignment.Top -> 0
                    VerticalAlignment.Center -> verticalSlack / 2
                    VerticalAlignment.Bottom -> verticalSlack
                }
            return IntOffset(x, y)
        }
    }

    /**
     * Stable token for the fixed-column grid component.
     */
    companion object {
        internal val TYPE: ElementType<GridElement, Node> =
            ElementType(
                elementClass = GridElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    validate(element.columns, element.horizontalSpacing, element.verticalSpacing)
                },
                createNode = { element ->
                    Node(
                        columns = element.columns,
                        horizontalSpacing = element.horizontalSpacing,
                        verticalSpacing = element.verticalSpacing,
                        contentAlignment = element.contentAlignment,
                    )
                },
                updateNode = { previous, current, node -> node.update(previous, current) },
            )

        private fun validate(
            columns: Int,
            horizontalSpacing: Int,
            verticalSpacing: Int,
        ) {
            require(0 < columns) { "Grid columns must be positive." }
            require(0 <= horizontalSpacing) { "Grid horizontal spacing must be non-negative." }
            require(0 <= verticalSpacing) { "Grid vertical spacing must be non-negative." }
        }

        private fun rowCount(
            childCount: Int,
            columns: Int,
        ): Int = childCount / columns + if (childCount % columns == 0) 0 else 1
    }
}
