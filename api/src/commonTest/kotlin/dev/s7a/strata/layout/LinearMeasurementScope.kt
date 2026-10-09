@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/** Independent public-scope adapter recording the entire original measure/layout callback order. */
internal class LinearMeasurementScope(
    val weights: MutableList<WeightParentData.Data?>,
    private val naturalSizes: List<IntSize>,
    private val failure: Failure? = null,
    private val overrideAlignment: Boolean = false,
) : MeasureScope, LayoutScope {
    override val childCount: Int get() = weights.size
    override var size: IntSize = IntSize.Zero
    val trace: MutableList<LinearMeasurementTrace> = mutableListOf()
    val bounds: MutableList<Constraints> = mutableListOf()
    val measuredSizes: MutableList<IntSize?> = MutableList(childCount) { null }
    var onMeasure: (() -> Unit)? = null
    var onPlace: (() -> Unit)? = null

    override fun <D : Any> childParentData(index: Int, key: ParentDataKey<D>): D? {
        val event = if (key === WeightParentData.KEY) LinearMeasurementTrace.WeightRead(index) else LinearMeasurementTrace.AlignmentRead(index)
        record(event)
        val value = when (key) {
            WeightParentData.KEY -> weights[index]
            RowAlignmentParentData.KEY -> if (overrideAlignment) RowAlignmentParentData.Data(VerticalAlignment.entries[index % VerticalAlignment.entries.size]) else null
            ColumnAlignmentParentData.KEY -> if (overrideAlignment) ColumnAlignmentParentData.Data(HorizontalAlignment.entries[index % HorizontalAlignment.entries.size]) else null
            else -> error("Unexpected parent-data key")
        }
        return value?.let(key::castErased)
    }

    override fun measureChild(index: Int, constraints: Constraints): IntSize {
        record(LinearMeasurementTrace.Measure(index, constraints))
        check(measuredSizes[index] == null)
        bounds += constraints
        onMeasure?.invoke()
        return constraints.constrain(naturalSizes[index]).also { measuredSizes[index] = it }
    }

    override fun measuredChildSize(index: Int): IntSize {
        val result = checkNotNull(measuredSizes[index])
        record(LinearMeasurementTrace.SizeRead(index, result))
        return result
    }

    override fun placeChild(index: Int, offset: IntOffset) {
        record(LinearMeasurementTrace.Place(index, offset))
        onPlace?.invoke()
    }

    private fun record(event: LinearMeasurementTrace) {
        trace += event
        val injected = failure
        if (injected != null && injected.event == event) throw injected.cause
    }

    /** One original throwable shared by both algorithm executions. */
    data class Failure(val event: LinearMeasurementTrace, val cause: Throwable)
}
