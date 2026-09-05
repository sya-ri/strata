package dev.s7a.strata.node

/**
 * A node that supplies an additional uniform transform for each placed direct child's effective subtree.
 *
 * Measurement and ordinary integer placement remain owned by [MeasureNode] and [LayoutNode].
 * After layout, the retained runtime queries this capability on the owning tree thread, scales child-local coordinates, and then adds the transform offset and child's ordinary placement.
 * Descendant portable painting, child clips, pointer hit testing and inverse local coordinates, focus visibility, and semantics bounds share that composed transform.
 * Root-overlay commands remain in root coordinates, while their anchor reflects transformed descendant geometry.
 * Opaque platform payloads remain unchanged, and the current core runtime rejects them during painting unless the composed transform is an exact integer translation.
 * Implementations must invalidate layout when a returned transform can change without remeasurement.
 *
 * Any failure escapes through the active layout or dependent pipeline operation and follows the owning tree's failure contract.
 */
public interface ChildTransformNode {
    /**
     * Returns the additional transform for one placed direct child.
     *
     * The runtime invokes this method on the owning tree thread only after successful layout and supplies a valid placed direct-child index.
     * Implementations retain ownership of their state; the returned immutable value may be retained by the runtime until layout is invalidated.
     *
     * @param index valid direct-child index in the current placed child set.
     * @return finite immutable transform combined with ordinary child placement.
     * @throws Throwable when resolving the transform fails; the exact failure propagates through the active tree operation.
     */
    public fun childTransform(index: Int): ChildTransform
}
