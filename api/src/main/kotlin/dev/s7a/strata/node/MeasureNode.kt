package dev.s7a.strata.node

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.MeasureScope

/**
 * A node that determines its size from constraints and optionally measured direct children.
 *
 * The returned size is retained until this node is measured again with different constraints or after measurement invalidation.
 * A clean equal-constraint pass may therefore skip this callback.
 */
public interface MeasureNode {
    /**
     * Measures this node on the owning tree thread.
     *
     * The scope is valid only for the duration of this callback.
     * Access after return or from another thread fails.
     * If a callback or scope operation throws after pipeline work begins, the owning tree is poisoned and cleanup is attempted.
     * This happens only when the exception escapes this callback.
     *
     * @param scope the current direct-child measurement scope.
     * @param constraints the size constraints for this node.
     * @return a size satisfying [constraints].
     */
    public fun measure(
        scope: MeasureScope,
        constraints: Constraints,
    ): IntSize
}
