package dev.s7a.strata.input

import dev.s7a.strata.geometry.IntOffset

/**
 * Adapter-recognized navigation gesture, dispatched without synthesizing a pointer press or acquiring focus.
 * The adapter owns recognition and physical pointer capture; each delivery hit-tests committed geometry.
 * A pan keeps its initial hit position while carrying incremental motion, so scrolling does not retarget to a moved row.
 */
public sealed interface PointerGesture {
    /**
     * Tree-coordinate hit position, also used as the local zoom anchor.
     */
    public val position: IntOffset

    /**
     * Incremental motion in logical pixels; positive deltas move displayed content toward increasing coordinates.
     * Core inverse-scales these deltas for each receiving node.
     */
    public data class Pan(
        override val position: IntOffset,
        public val deltaX: Double,
        public val deltaY: Double,
    ) : PointerGesture {
        init {
            require(deltaX.isFinite() && deltaY.isFinite()) { "Pan displacement must be finite." }
        }
    }

    /**
     * Positive finite magnification relative to the previous delivery, anchored at position.
     */
    public data class Zoom(
        override val position: IntOffset,
        public val factor: Double,
    ) : PointerGesture {
        init {
            require(factor.isFinite() && 0.0 < factor) { "Zoom magnification must be positive and finite." }
        }
    }
}
