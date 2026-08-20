package dev.s7a.strata.layout

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.node.LayoutNode

/**
 * Direct-child placement scope for one parent layout pass.
 *
 * The scope is owned by the [LayoutNode.layout] callback and is valid only until that callback returns.
 * Every access must use the tree's owner thread.
 * Access after the callback or from another thread fails.
 * Only children measured in the current pass may be placed, and each such child may be placed once.
 * An invalid index, an unmeasured child, or a second placement throws.
 * The tree is poisoned once pipeline work has started only when that exception escapes the owning layout callback.
 */
public interface LayoutScope : ParentDataScope {
    /**
     * The size assigned to this node.
     *
     * This property is available only during the owning layout callback.
     */
    public val size: IntSize

    /**
     * The number of direct retained children.
     *
     * This property is available only during the owning layout callback.
     */
    public val childCount: Int

    /**
     * Returns the size measured for a direct child.
     *
     * @param index the absolute direct-child index.
     * @return the measured child size.
     * @throws IllegalArgumentException when [index] is outside the direct-child range.
     * @throws IllegalStateException when the child was not measured in this pass or the scope is no longer valid.
     */
    public fun measuredChildSize(index: Int): IntSize

    /**
     * Places one measured direct child at an offset in this node's local coordinates.
     *
     * @param index the absolute direct-child index.
     * @param offset the checked local placement offset.
     * @throws IllegalArgumentException when [index] is outside the direct-child range.
     * @throws IllegalStateException when the child was not measured, was already placed, or the scope is no longer valid.
     */
    public fun placeChild(
        index: Int,
        offset: IntOffset,
    )
}
