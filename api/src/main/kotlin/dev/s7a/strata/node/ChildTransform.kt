package dev.s7a.strata.node

import dev.s7a.strata.geometry.DoubleOffset

/**
 * Immutable uniform transform from one placed child's local coordinates into its parent's coordinates.
 *
 * A child point is mapped by multiplying both axes by [scale], then adding [offset] and the child's ordinary integer placement.
 * The runtime composes this transform through the effective descendant subtree for geometry, painting, clipping, input, focus visibility, and semantics.
 * The value owns no external resources and is safe to share between threads.
 *
 * @property scale finite positive multiplier applied equally to both axes.
 * @property offset finite displacement applied after scaling and before the child's ordinary placement.
 * @throws IllegalArgumentException when [scale] is not finite and positive.
 */
public data class ChildTransform(
    public val scale: Double,
    public val offset: DoubleOffset = DoubleOffset.Zero,
) {
    init {
        require(scale.isFinite() && 0.0 < scale) { "Child transform scale must be finite and positive." }
    }

    /**
     * Common child transforms.
     */
    public companion object {
        /**
         * Leaves child coordinates unchanged.
         */
        public val Identity: ChildTransform = ChildTransform(scale = 1.0)
    }
}
