package dev.s7a.strata.node

/**
 * A node whose effective descendants are clipped to this node's measured bounds for painting and pointer hit testing.
 *
 * The retained runtime reads this marker on the owning tree thread after this node's regular paint callback and before traversing its effective children.
 * Nested clips intersect, empty bounds hide all descendant drawing, and this node's own regular and overlay commands remain outside the child clip.
 * Pointer traversal skips the entire effective descendant subtree when the event position lies outside this node's half-open bounds, while this node's own input behavior remains governed by its bounds.
 */
public interface ClipChildrenNode
