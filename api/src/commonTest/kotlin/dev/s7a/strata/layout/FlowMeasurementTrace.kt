package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize

/**
 * Value-only complete callback trace, independent from candidate partition helpers.
 */
internal sealed interface FlowMeasurementTrace {
    /**
     * A child measurement with its exact input constraints.
     */
    data class Measure(
        val index: Int,
        val constraints: Constraints,
    ) : FlowMeasurementTrace

    /**
     * An immutable measured-child size read.
     */
    data class SizeRead(
        val index: Int,
        val size: IntSize,
    ) : FlowMeasurementTrace

    /**
     * A cross-axis parent alignment read before placement.
     */
    data class AlignmentRead(
        val index: Int,
    ) : FlowMeasurementTrace

    /**
     * A placement with its exact offset.
     */
    data class Place(
        val index: Int,
        val offset: IntOffset,
    ) : FlowMeasurementTrace
}
