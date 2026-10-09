package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize

/** Value-only complete callback trace, independent from candidate partition helpers. */
internal sealed interface FlowMeasurementTrace {
    data class Measure(val index: Int, val constraints: Constraints) : FlowMeasurementTrace
    data class SizeRead(val index: Int, val size: IntSize) : FlowMeasurementTrace
    data class AlignmentRead(val index: Int) : FlowMeasurementTrace
    data class Place(val index: Int, val offset: IntOffset) : FlowMeasurementTrace
}
