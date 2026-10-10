@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Public-scope adapter preserving valid child bounds and recording complete successful/exceptional prefixes.
 */
internal class FlowMeasurementScope(
    private val naturalSizes: List<IntSize>,
    private val failure: Failure? = null,
    private val overrideAlignment: Boolean = false,
) : MeasureScope, LayoutScope {
    override val childCount: Int get() = naturalSizes.size
    override var size: IntSize = IntSize.Zero
    val trace: MutableList<FlowMeasurementTrace> = mutableListOf()
    val measuredSizes: MutableList<IntSize?> = MutableList(childCount) { null }
    var onPlace: (() -> Unit)? = null

    override fun measureChild(index: Int, constraints: Constraints): IntSize {
        record(FlowMeasurementTrace.Measure(index, constraints))
        check(measuredSizes[index] == null)
        return constraints.constrain(naturalSizes[index]).also { measuredSizes[index] = it }
    }

    override fun <D : Any> childParentData(index: Int, key: ParentDataKey<D>): D? {
        check(key === FlowRowAlignmentParentData.KEY)
        record(FlowMeasurementTrace.AlignmentRead(index))
        val value = if (overrideAlignment) FlowRowAlignmentParentData.Data(VerticalAlignment.entries[index % VerticalAlignment.entries.size]) else null
        return value?.let(key::castErased)
    }

    override fun measuredChildSize(index: Int): IntSize {
        val result = checkNotNull(measuredSizes[index])
        record(FlowMeasurementTrace.SizeRead(index, result))
        return result
    }

    override fun placeChild(index: Int, offset: IntOffset) {
        record(FlowMeasurementTrace.Place(index, offset))
        onPlace?.invoke()
    }

    private fun record(event: FlowMeasurementTrace) {
        trace += event
        val injected = failure
        if (injected != null && injected.event == event) throw injected.cause
    }

    /**
     * An original throwable injected at one actual scope attempt.
     */
    data class Failure(val event: FlowMeasurementTrace, val cause: Throwable)
}
