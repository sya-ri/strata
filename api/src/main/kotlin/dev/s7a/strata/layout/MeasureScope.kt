package dev.s7a.strata.layout

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.MeasureNode

/**
 * Direct-child measurement scope for one parent measurement pass.
 *
 * The scope is owned by the [MeasureNode.measure] callback and is valid only until that callback returns.
 * Every access must use the tree's owner thread.
 * Access after the callback or from another thread fails.
 * Each direct child can be measured at most once through this scope.
 * An index outside the direct-child range or a second measurement of the same child throws.
 * The tree is poisoned once pipeline work has started only when that exception escapes the owning measure callback.
 */
public interface MeasureScope {
    /**
     * The number of direct retained children.
     *
     * This property is available only during the owning measure callback.
     */
    public val childCount: Int

    /**
     * Measures one direct child exactly once.
     *
     * @param index the absolute direct-child index.
     * @param constraints the child's constraints.
     * @return the child's measured size.
     * @throws IllegalArgumentException when [index] is outside the direct-child range.
     * @throws IllegalStateException when this child was already measured or the scope is no longer valid.
     */
    public fun measureChild(
        index: Int,
        constraints: Constraints,
    ): IntSize
}
