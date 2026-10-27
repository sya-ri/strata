package dev.s7a.strata.node

/**
 * Controls presentation of a modifier-owned root overlay.
 * Visibility changes must invalidate layout; overlay layout uses root coordinates and viewport constraints.
 * Content never participates in pointer dispatch or focus traversal.
 */
public interface RootOverlayContentNode {
    /**
     * Whether the retained overlay is eligible for layout and painting this frame.
     */
    public val overlayVisible: Boolean
}
