package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
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
 * Frozen complete LinearElement.Node algorithms from 5b0bc358d6dc8346fad091be6347036e96a55dfd,
 * blob 25c61d700e7489517161d23d88e4d3e340dc2739.
 * Only API-private orientation/parent-data keys and checked conversion are adapted to independent public SPI inputs.
 * Measure/layout and weighted arithmetic remain the original complete algorithms, including their snapshots.
 */
@Suppress("TooManyFunctions")
internal class LinearReferenceNode(
    private var orientation: Orientation,
    private var spacing: Int,
    private var arrangement: Arrangement,
) : RetainedNode(),
    MeasureNode,
    LayoutNode {
    override fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize {
        val plan = collectWeights(scope)
        val childSizes = arrayOfNulls<IntSize>(scope.childCount)
        val fixedMain = measureFixedChildren(scope, constraints, plan.weights, childSizes)
        measureWeightedChildren(scope, constraints, plan, fixedMain, childSizes)
        return constraints.constrain(naturalSize(childSizes))
    }

    override fun layout(scope: LayoutScope) {
        val childCount = scope.childCount
        var totalMain = 0L
        val childSizes = arrayOfNulls<IntSize>(childCount)
        for (index in 0 until childCount) {
            val size = scope.measuredChildSize(index)
            childSizes[index] = size
            totalMain += mainExtent(size)
        }
        val gapCount = if (0 < childCount) childCount - 1 else 0
        val fixedGaps = spacing.toLong() * gapCount
        val totalWithGaps = totalMain + fixedGaps
        val containerMain = mainExtent(scope.size)
        val slack = (containerMain.toLong() - totalWithGaps).coerceAtLeast(0L).toInt()
        var prefix = 0L
        for (index in 0 until childCount) {
            val size = requireNotNull(childSizes[index]) { "A linear child was not measured." }
            val arrangementOffset = arrangement.offset(slack, index, childCount)
            val spacingOffset = spacing.toLong() * index
            val mainPosition = (arrangementOffset + prefix + spacingOffset).toIntExact()
            val crossPosition = crossPosition(scope, index, size)
            val offset =
                if (orientation.axis == Axis.Horizontal) {
                    IntOffset(mainPosition, crossPosition)
                } else {
                    IntOffset(crossPosition, mainPosition)
                }
            scope.placeChild(index, offset)
            prefix += mainExtent(size)
        }
    }

    /**
     * Applies a changed immutable description to this retained node.
     *
     * @param previous the previously retained description.
     * @param current the incoming description.
     * @return the phases affected by the changed properties.
     */
    internal fun update(
        previous: LinearReferenceElement,
        current: LinearReferenceElement,
    ): DirtyMask {
        var dirty = DirtyMask.None
        val axisChanged = previous.orientation.axis != current.orientation.axis
        if (
            axisChanged ||
            previous.spacing != current.spacing
        ) {
            dirty += DirtyMask.of(DirtyPhase.Measure)
        }
        if (
            axisChanged.not() &&
            (previous.arrangement != current.arrangement || previous.orientation != current.orientation)
        ) {
            dirty += DirtyMask.of(DirtyPhase.Layout)
        }
        orientation = current.orientation
        spacing = current.spacing
        arrangement = current.arrangement
        return dirty
    }

    private class WeightPlan(
        val weights: Array<WeightParentData.Data?>,
        val weighted: Boolean,
    )

    private fun collectWeights(scope: MeasureScope): WeightPlan {
        val weights = arrayOfNulls<WeightParentData.Data>(scope.childCount)
        var weighted = false
        for (index in 0 until scope.childCount) {
            val weight = scope.childParentData(index, WeightParentData.KEY)
            weights[index] = weight
            if (weight != null) {
                weighted = true
            }
        }
        return WeightPlan(weights, weighted)
    }

    private fun measureFixedChildren(
        scope: MeasureScope,
        constraints: Constraints,
        weights: Array<WeightParentData.Data?>,
        childSizes: Array<IntSize?>,
    ): Long {
        var fixedMain = 0L
        for (index in 0 until scope.childCount) {
            if (weights[index] == null) {
                val size = scope.measureChild(index, fixedConstraints(constraints))
                childSizes[index] = size
                fixedMain += mainExtent(size)
            }
        }
        return fixedMain
    }

    private fun measureWeightedChildren(
        scope: MeasureScope,
        constraints: Constraints,
        plan: WeightPlan,
        fixedMain: Long,
        childSizes: Array<IntSize?>,
    ) {
        if (plan.weighted.not()) {
            return
        }
        if (mainMaximum(constraints) == Int.MAX_VALUE) {
            measureIntrinsicWeightedChildren(scope, constraints, plan.weights, childSizes)
            return
        }
        val available = availableWeightSpace(scope.childCount, constraints, fixedMain)
        val slots = allocateWeightedSlots(plan.weights, available)
        for (index in 0 until scope.childCount) {
            val weight = plan.weights[index]
            if (weight != null) {
                childSizes[index] =
                    scope.measureChild(index, weightedConstraints(constraints, slots[index], weight.fill))
            }
        }
    }

    private fun measureIntrinsicWeightedChildren(
        scope: MeasureScope,
        constraints: Constraints,
        weights: Array<WeightParentData.Data?>,
        childSizes: Array<IntSize?>,
    ) {
        for (index in 0 until scope.childCount) {
            if (weights[index] != null) {
                childSizes[index] = scope.measureChild(index, intrinsicWeightedConstraints(constraints))
            }
        }
    }

    private fun availableWeightSpace(
        childCount: Int,
        constraints: Constraints,
        fixedMain: Long,
    ): Int {
        val gapCount = if (0 < childCount) childCount - 1 else 0
        val fixedGaps = spacing.toLong() * gapCount
        return (mainMaximum(constraints).toLong() - fixedMain - fixedGaps).coerceAtLeast(0L).toInt()
    }

    private fun allocateWeightedSlots(
        weights: Array<WeightParentData.Data?>,
        available: Int,
    ): IntArray {
        val slots = IntArray(weights.size)
        val spacePerWeight = available / weights.sumOf { it?.weight?.toDouble() ?: 0.0 }
        val lastWeighted = weights.indexOfLast { it != null }
        var remaining = available
        for (index in weights.indices) {
            val weight = weights[index] ?: continue
            val slot =
                if (index == lastWeighted) {
                    remaining
                } else {
                    (spacePerWeight * weight.weight).toInt().coerceIn(0, remaining)
                }
            slots[index] = slot
            remaining -= slot
        }
        return slots
    }

    private fun naturalSize(childSizes: Array<IntSize?>): IntSize {
        var naturalMain = 0L
        var naturalCross = 0
        for (index in childSizes.indices) {
            val size = requireNotNull(childSizes[index]) { "A linear child was not measured." }
            naturalMain += mainExtent(size)
            val cross = crossExtent(size)
            if (naturalCross < cross) {
                naturalCross = cross
            }
        }
        if (1 < childSizes.size) {
            naturalMain += spacing.toLong() * (childSizes.size - 1)
        }
        return if (orientation.axis == Axis.Horizontal) {
            IntSize(naturalMain.toIntExact(), naturalCross)
        } else {
            IntSize(naturalCross, naturalMain.toIntExact())
        }
    }

    private fun fixedConstraints(constraints: Constraints): Constraints =
        Constraints(
            minWidth = 0,
            maxWidth = constraints.maxWidth,
            minHeight = 0,
            maxHeight = constraints.maxHeight,
        )

    private fun intrinsicWeightedConstraints(constraints: Constraints): Constraints =
        if (orientation.axis == Axis.Horizontal) {
            Constraints(
                minWidth = 0,
                maxWidth = Int.MAX_VALUE,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )
        } else {
            Constraints(
                minWidth = 0,
                maxWidth = constraints.maxWidth,
                minHeight = 0,
                maxHeight = Int.MAX_VALUE,
            )
        }

    private fun weightedConstraints(
        constraints: Constraints,
        slot: Int,
        fill: Boolean,
    ): Constraints {
        val mainMinimum = if (fill) slot else 0
        return if (orientation.axis == Axis.Horizontal) {
            Constraints(
                minWidth = mainMinimum,
                maxWidth = slot,
                minHeight = 0,
                maxHeight = constraints.maxHeight,
            )
        } else {
            Constraints(
                minWidth = 0,
                maxWidth = constraints.maxWidth,
                minHeight = mainMinimum,
                maxHeight = slot,
            )
        }
    }

    private fun mainMaximum(constraints: Constraints): Int = if (orientation.axis == Axis.Horizontal) constraints.maxWidth else constraints.maxHeight

    private fun mainExtent(size: IntSize): Int = if (orientation.axis == Axis.Horizontal) size.width else size.height

    private fun crossExtent(size: IntSize): Int = if (orientation.axis == Axis.Horizontal) size.height else size.width

    private fun crossPosition(
        scope: LayoutScope,
        index: Int,
        childSize: IntSize,
    ): Int {
        val containerCross = if (orientation.axis == Axis.Horizontal) scope.size.height else scope.size.width
        val difference = containerCross - crossExtent(childSize)
        return when (val policy = orientation) {
            is Orientation.Row -> {
                val alignment = scope.childParentData(index, RowAlignmentParentData.KEY)?.alignment ?: policy.alignment
                when (alignment) {
                    VerticalAlignment.Top -> 0
                    VerticalAlignment.Center -> difference / 2
                    VerticalAlignment.Bottom -> difference
                }
            }

            is Orientation.Column -> {
                val alignment = scope.childParentData(index, ColumnAlignmentParentData.KEY)?.alignment ?: policy.alignment
                when (alignment) {
                    HorizontalAlignment.Start -> 0
                    HorizontalAlignment.Center -> difference / 2
                    HorizontalAlignment.End -> difference
                }
            }
        }
    }

    /**
     * Type-safe reference orientation; copied original defaults remain separate from candidate code.
     */
    sealed interface Orientation {
        val axis: Axis
        data class Row(val alignment: VerticalAlignment) : Orientation { override val axis: Axis = Axis.Horizontal }
        data class Column(val alignment: HorizontalAlignment) : Orientation { override val axis: Axis = Axis.Vertical }
    }

    /**
     * Original main-axis branches.
     */
    enum class Axis { Horizontal, Vertical }

    /**
     * Independent typed weight key and immutable original scalar inputs.
     */
    object WeightParentData {
        data class Data(val weight: Float, val fill: Boolean)
        val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)

        /**
         * Active reference provider; measurement invalidation matches the original standard modifier.
         */
        data class Element(val data: Data) : ModifierElement {
            override val type: ModifierNodeType<*, *> get() = TYPE
        }

        /**
         * Owner-confined current reference weight.
         */
        class ProviderNode(var data: Data) : ModifierNode(), ParentDataModifierNode<Data> {
            override val parentDataKey: ParentDataKey<Data> get() = KEY
            override fun parentData(): Data = data
        }

        private val TYPE = ModifierNodeType(
            elementClass = Element::class,
            nodeClass = ProviderNode::class,
            validateLocal = { element -> require(element.data.weight.isFinite() && 0 < element.data.weight) },
            createNode = { element -> ProviderNode(element.data) },
            updateNode = { previous, current, node ->
                node.data = current.data
                if (previous.data == current.data) DirtyMask.None else DirtyMask.of(DirtyPhase.Measure)
            },
        )
    }

    /**
     * Original row cross-axis value and referential key.
     */
    object RowAlignmentParentData {
        data class Data(val alignment: VerticalAlignment)
        val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)
    }

    /**
     * Original column cross-axis value and referential key.
     */
    object ColumnAlignmentParentData {
        data class Data(val alignment: HorizontalAlignment)
        val KEY: ParentDataKey<Data> = ParentDataKey(Data::class)
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

    private fun Long.toIntExact(): Int {
        if (this < Int.MIN_VALUE.toLong() || Int.MAX_VALUE.toLong() < this) throw ArithmeticException("Integer overflow")
        return toInt()
    }
}
