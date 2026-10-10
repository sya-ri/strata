package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize

/**
 * Value-only callback trace for the independent complete linear algorithms.
 */
internal sealed interface LinearMeasurementTrace {
    /**
     * A selected weight provider read, before any child measurement.
     */
    data class WeightRead(
        val index: Int,
    ) : LinearMeasurementTrace

    /**
     * A child invocation with its exact numeric bounds.
     */
    data class Measure(
        val index: Int,
        val constraints: Constraints,
    ) : LinearMeasurementTrace

    /**
     * A layout preflight size read.
     */
    data class SizeRead(
        val index: Int,
        val size: IntSize,
    ) : LinearMeasurementTrace

    /**
     * A cross-axis parent-data lookup at placement time.
     */
    data class AlignmentRead(
        val index: Int,
    ) : LinearMeasurementTrace

    /**
     * A checked placement after the full size preflight.
     */
    data class Place(
        val index: Int,
        val offset: IntOffset,
    ) : LinearMeasurementTrace
}
