package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.layout.ParentDataKey
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.modifier.ModifierElement
import dev.s7a.strata.modifier.ModifierNodeType
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.ModifierNode
import dev.s7a.strata.node.ParentDataModifierNode
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Complete original FlowRowElement.Node from 5b0bc358d6dc8346fad091be6347036e96a55dfd,
 * blob 143f3cccfe16f1f9cc0ea47db275e1af8024bed2.
 * Only the API-private parent-data key and checked conversion are adapted to independent public SPI inputs.
 * The original measure/layout algorithms, row records, cutoffs and checked conversions remain intact.
 */
internal class FlowReferenceNode(
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
            placeRow(scope, row, childSizes, top.toIntExact())
            top += row.height
            if (index < rows.lastIndex) {
                top += verticalSpacing
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
        previous: FlowReferenceElement,
        current: FlowReferenceElement,
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
                    width + horizontalSpacing + child.width
                }
            if (start < index && maximumWidth != Int.MAX_VALUE && maximumWidth.toLong() < nextWidth) {
                rows += Row(start, index, width.toIntExact(), height)
                start = index
                width = child.width.toLong()
                height = child.height
            } else {
                width = nextWidth
                height = maxOf(height, child.height)
            }
        }
        if (start < childSizes.size) {
            rows += Row(start, childSizes.size, width.toIntExact(), height)
        }
        return rows
    }

    private fun naturalSize(rows: List<Row>): IntSize {
        var width = 0
        var height = 0L
        for (index in rows.indices) {
            val row = rows[index]
            width = maxOf(width, row.width)
            height += row.height
            if (index < rows.lastIndex) {
                height += verticalSpacing
            }
        }
        return IntSize(width, height.toIntExact())
    }

    private fun placeRow(
        scope: LayoutScope,
        row: Row,
        childSizes: List<IntSize>,
        top: Int,
    ) {
        val slack = (scope.size.width - row.width).coerceAtLeast(0)
        val childCount = row.end - row.start
        var left = 0L
        for (index in row.start until row.end) {
            val child = childSizes[index]
            val extra = horizontalArrangement.offset(slack, index - row.start, childCount)
            val verticalOffset = verticalOffset(scope, index, row.height, child.height)
            scope.placeChild(
                index,
                IntOffset((left + extra).toIntExact(), (top.toLong() + verticalOffset).toIntExact()),
            )
            left += child.width
            if (index < row.end - 1) {
                left += horizontalSpacing
            }
        }
    }

    private fun verticalOffset(
        scope: LayoutScope,
        index: Int,
        rowHeight: Int,
        childHeight: Int,
    ): Int {
        val alignment = scope.childParentData(index, AlignmentParentData.KEY)?.alignment ?: verticalAlignment
        val slack = rowHeight - childHeight
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

    /**
     * Active test-only parent data with the original measure invalidation on override changes.
     */
    object AlignmentParentData {
        /**
         * Immutable row-local placement override.
         */
        data class Data(
            val alignment: VerticalAlignment,
        )

        /**
         * Referential key kept independent of the standard parent's key.
         */
        val KEY = ParentDataKey(Data::class)

        /**
         * Immutable active modifier description.
         */
        data class Element(
            val data: Data,
        ) : ModifierElement {
            override val type: ModifierNodeType<*, *> get() = TYPE
        }

        /**
         * Owner-thread provider with no external resource or operation history.
         */
        class Provider(
            private var data: Data,
        ) : ModifierNode(),
            ParentDataModifierNode<Data> {
            override val parentDataKey: ParentDataKey<Data> get() = KEY

            override fun parentData(): Data = data

            /**
             * Applies original parent-data invalidation on a changed value.
             */
            fun update(next: Data): DirtyMask {
                val changed = data != next
                data = next
                return if (changed) DirtyMask.of(DirtyPhase.Measure) else DirtyMask.None
            }
        }

        private val TYPE =
            ModifierNodeType(
                elementClass = Element::class,
                nodeClass = Provider::class,
                validateLocal = { _ -> },
                createNode = { element -> Provider(element.data) },
                updateNode = { _, current, node -> node.update(current.data) },
            )
    }

    /**
     * Original offset arithmetic from 5b0bc358d6dc8346fad091be6347036e96a55dfd,
     * ArrangementOffsets.kt blob 7b9917f5f43e62e39d12f0af7938a3bb4e2cbaf9,
     * UTF-8/LF SHA-256 7246071b27bfda3068e893682f6053cd6f3c394049ddf0990e024a6470f17577.
     * Reference-local visibility preserves the API-private helper without changing public SPI.
     */
    private fun Arrangement.offset(
        slack: Int,
        index: Int,
        childCount: Int,
    ): Long =
        when (this) {
            Arrangement.Start -> {
                0L
            }

            Arrangement.Center -> {
                slack / 2L
            }

            Arrangement.End -> {
                slack.toLong()
            }

            Arrangement.SpaceBetween -> {
                if (1 < childCount) {
                    slack.toLong() * index / (childCount - 1)
                } else {
                    0L
                }
            }

            Arrangement.SpaceAround -> {
                slack.toLong() * (2L * index + 1L) / (2L * childCount)
            }

            Arrangement.SpaceEvenly -> {
                slack.toLong() * (index.toLong() + 1L) / (childCount.toLong() + 1L)
            }
        }

    // Identical checked conversion copied from the original API-private helper.
    private fun Long.toIntExact(): Int {
        if (this < Int.MIN_VALUE.toLong() || Int.MAX_VALUE.toLong() < this) {
            throw ArithmeticException("Integer overflow")
        }
        return toInt()
    }
}
