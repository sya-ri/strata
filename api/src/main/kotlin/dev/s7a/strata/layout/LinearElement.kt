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
import java.math.BigInteger
import dev.s7a.strata.node.Node as RetainedNode

/**
 * Internal immutable description shared by row and column declarations.
 *
 * The base [Element] snapshots the direct children and retains the immutable modifier chain.
 * After submission, the runtime owns this description snapshot and the retained [Node] created from it.
 * The retained node propagates checked constraint, extent, weight-allocation, and placement arithmetic failures to the active tree operation.
 *
 * @property orientation the type-safe main axis and default cross-axis policy.
 * @property spacing the non-negative fixed gap between adjacent direct children.
 * @property arrangement the main-axis slack distribution policy.
 * @param key optional stable identity among direct siblings.
 * @param children direct logical children in declaration order.
 * @param modifier active behavior applied around the retained component.
 * @throws IllegalArgumentException when [spacing] is negative.
 */
internal class LinearElement(
    val orientation: LinearOrientation,
    val spacing: Int,
    val arrangement: Arrangement,
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
        require(0 <= spacing) { "Linear layout spacing must be non-negative." }
    }

    /**
     * Retained node that measures and places one logical row or column.
     *
     * @param orientation the type-safe default cross-axis policy of this node.
     * @param spacing the checked fixed gap between children.
     * @param arrangement the initial main-axis arrangement.
     */
    @Suppress("TooManyFunctions")
    internal class Node(
        orientation: LinearOrientation,
        spacing: Int,
        arrangement: Arrangement,
    ) : RetainedNode(),
        MeasureNode,
        LayoutNode {
        private var orientation: LinearOrientation = orientation
        private var spacing: Int = spacing
        private var arrangement: Arrangement = arrangement

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
                totalMain = Math.addExact(totalMain, mainExtent(size).toLong())
            }
            val gapCount = if (0 < childCount) childCount - 1 else 0
            val fixedGaps = Math.multiplyExact(spacing.toLong(), gapCount.toLong())
            val totalWithGaps = Math.addExact(totalMain, fixedGaps)
            val containerMain = mainExtent(scope.size)
            val slack =
                if (totalWithGaps <= containerMain.toLong()) {
                    containerMain.toLong() - totalWithGaps
                } else {
                    0L
                }
            var prefix = 0L
            for (index in 0 until childCount) {
                val size = requireNotNull(childSizes[index]) { "A linear child was not measured." }
                val arrangementOffset = arrangementOffset(slack, index, childCount)
                val spacingOffset = Math.multiplyExact(spacing.toLong(), index.toLong())
                val mainPosition =
                    Math.addExact(
                        Math.addExact(arrangementOffset, prefix),
                        spacingOffset,
                    )
                val crossPosition = crossPosition(scope, index, size)
                val offset =
                    if (orientation.axis == LinearAxis.Horizontal) {
                        IntOffset(Math.toIntExact(mainPosition), crossPosition)
                    } else {
                        IntOffset(crossPosition, Math.toIntExact(mainPosition))
                    }
                scope.placeChild(index, offset)
                prefix = Math.addExact(prefix, mainExtent(size).toLong())
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
            previous: LinearElement,
            current: LinearElement,
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
                    fixedMain = Math.addExact(fixedMain, mainExtent(size).toLong())
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
        ): Long {
            val gapCount = if (0 < childCount) childCount - 1 else 0
            val fixedGaps = Math.multiplyExact(spacing.toLong(), gapCount.toLong())
            val parentRemaining = Math.subtractExact(mainMaximum(constraints).toLong(), fixedMain)
            val remaining = Math.subtractExact(parentRemaining, fixedGaps)
            return remaining.coerceAtLeast(0L)
        }

        private fun allocateWeightedSlots(
            weights: Array<WeightParentData.Data?>,
            available: Long,
        ): IntArray {
            val slots = IntArray(weights.size)
            val exactWeights = arrayOfNulls<ExactWeight>(weights.size)
            var minimumExponent = Int.MAX_VALUE
            for (index in weights.indices) {
                val weight = weights[index]
                if (weight != null) {
                    val exactWeight = decodeWeight(weight.weight)
                    exactWeights[index] = exactWeight
                    if (exactWeight.exponent < minimumExponent) {
                        minimumExponent = exactWeight.exponent
                    }
                }
            }
            val numerators = arrayOfNulls<BigInteger>(weights.size)
            var total = BigInteger.ZERO
            for (index in weights.indices) {
                val exactWeight = exactWeights[index]
                if (exactWeight != null) {
                    val numerator = exactWeight.significand.shiftLeft(exactWeight.exponent - minimumExponent)
                    numerators[index] = numerator
                    total = total.add(numerator)
                }
            }
            check(total.signum() == 1) { "Linear layout weight total must be positive." }
            val availableValue = BigInteger.valueOf(available)
            var allocated = 0L
            var lastWeighted = -1
            for (index in weights.indices) {
                if (weights[index] != null) {
                    lastWeighted = index
                }
            }
            for (index in weights.indices) {
                val weight = weights[index]
                if (weight != null) {
                    val slot =
                        if (index == lastWeighted) {
                            Math.subtractExact(available, allocated)
                        } else {
                            val numerator = requireNotNull(numerators[index])
                            availableValue.multiply(numerator).divide(total).longValueExact()
                        }
                    check(0 <= slot) { "Linear layout weight allocation became negative." }
                    allocated = Math.addExact(allocated, slot)
                    check(allocated <= available) { "Linear layout weight allocation exceeded available space." }
                    slots[index] = Math.toIntExact(slot)
                }
            }
            check(allocated == available) { "Linear layout weight allocation did not consume available space." }
            return slots
        }

        private data class ExactWeight(
            val significand: BigInteger,
            val exponent: Int,
        )

        private fun decodeWeight(weight: Float): ExactWeight {
            val bits = weight.toRawBits()
            val rawExponent = (bits ushr 23) and 0xff
            val fraction = bits and 0x7fffff
            return if (rawExponent == 0) {
                ExactWeight(BigInteger.valueOf(fraction.toLong()), -149)
            } else {
                val significand = (1 shl 23) or fraction
                ExactWeight(BigInteger.valueOf(significand.toLong()), rawExponent - 127 - 23)
            }
        }

        private fun naturalSize(childSizes: Array<IntSize?>): IntSize {
            var naturalMain = 0L
            var naturalCross = 0
            for (index in childSizes.indices) {
                val size = requireNotNull(childSizes[index]) { "A linear child was not measured." }
                naturalMain = Math.addExact(naturalMain, mainExtent(size).toLong())
                val cross = crossExtent(size)
                if (naturalCross < cross) {
                    naturalCross = cross
                }
            }
            if (1 < childSizes.size) {
                naturalMain =
                    Math.addExact(
                        naturalMain,
                        Math.multiplyExact(spacing.toLong(), (childSizes.size - 1).toLong()),
                    )
            }
            return if (orientation.axis == LinearAxis.Horizontal) {
                IntSize(Math.toIntExact(naturalMain), naturalCross)
            } else {
                IntSize(naturalCross, Math.toIntExact(naturalMain))
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
            if (orientation.axis == LinearAxis.Horizontal) {
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
            return if (orientation.axis == LinearAxis.Horizontal) {
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

        private fun mainMaximum(constraints: Constraints): Int = if (orientation.axis == LinearAxis.Horizontal) constraints.maxWidth else constraints.maxHeight

        private fun mainExtent(size: IntSize): Int = if (orientation.axis == LinearAxis.Horizontal) size.width else size.height

        private fun crossExtent(size: IntSize): Int = if (orientation.axis == LinearAxis.Horizontal) size.height else size.width

        private fun arrangementOffset(
            slack: Long,
            index: Int,
            childCount: Int,
        ): Long =
            when (arrangement) {
                Arrangement.Start -> {
                    0L
                }

                Arrangement.Center -> {
                    slack / 2L
                }

                Arrangement.End -> {
                    slack
                }

                Arrangement.SpaceBetween -> {
                    if (1 < childCount) {
                        Math.multiplyExact(slack, index.toLong()) / (childCount - 1).toLong()
                    } else {
                        0L
                    }
                }

                Arrangement.SpaceAround -> {
                    val numerator =
                        Math.multiplyExact(
                            slack,
                            Math.addExact(Math.multiplyExact(2L, index.toLong()), 1L),
                        )
                    numerator / Math.multiplyExact(2L, childCount.toLong())
                }

                Arrangement.SpaceEvenly -> {
                    Math.multiplyExact(slack, Math.addExact(index.toLong(), 1L)) /
                        Math.addExact(childCount.toLong(), 1L)
                }
            }

        private fun crossPosition(
            scope: LayoutScope,
            index: Int,
            childSize: IntSize,
        ): Int {
            val containerCross = if (orientation.axis == LinearAxis.Horizontal) scope.size.height else scope.size.width
            val difference = Math.subtractExact(containerCross, crossExtent(childSize))
            return when (val policy = orientation) {
                is LinearOrientation.Row -> {
                    val alignment = scope.childParentData(index, RowAlignmentParentData.KEY)?.alignment ?: policy.alignment
                    when (alignment) {
                        VerticalAlignment.Top -> 0
                        VerticalAlignment.Center -> difference / 2
                        VerticalAlignment.Bottom -> difference
                    }
                }

                is LinearOrientation.Column -> {
                    val alignment = scope.childParentData(index, ColumnAlignmentParentData.KEY)?.alignment ?: policy.alignment
                    when (alignment) {
                        HorizontalAlignment.Start -> 0
                        HorizontalAlignment.Center -> difference / 2
                        HorizontalAlignment.End -> difference
                    }
                }
            }
        }
    }

    /**
     * Stable token for both axis variants of the linear component.
     */
    companion object {
        internal val TYPE: ElementType<LinearElement, Node> =
            ElementType(
                elementClass = LinearElement::class,
                nodeClass = Node::class,
                validateLocal = { element ->
                    require(0 <= element.spacing) { "Linear layout spacing must be non-negative." }
                },
                createNode = { element ->
                    Node(
                        element.orientation,
                        element.spacing,
                        element.arrangement,
                    )
                },
                updateNode = { previous, current, node -> node.update(previous, current) },
            )
    }
}
