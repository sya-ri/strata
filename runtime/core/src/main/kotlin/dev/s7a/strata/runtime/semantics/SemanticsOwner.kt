package dev.s7a.strata.runtime.semantics

import dev.s7a.strata.geometry.IntRect

/**
 * Detached native accessibility placement for one retained logical owner.
 * Identity is tree-local and stable until removal; no node, callback or application reference escapes.
 * Paint order is the command insertion position before the owning semantic surface, not a work counter.
 * Visible bounds intersect the viewport and every clipping ancestor.
 */
public data class SemanticsOwner(
    public val identity: Long,
    public val focusable: Boolean,
    public val focused: Boolean,
    public val paintOrder: Int,
    public val contentPaintOrder: Int,
    public val visibleBounds: IntRect,
)
