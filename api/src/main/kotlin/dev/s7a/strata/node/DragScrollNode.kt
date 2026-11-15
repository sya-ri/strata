package dev.s7a.strata.node

import dev.s7a.strata.component.DragScrollOptions

/**
 * A scroll viewport whose content supports tap-or-drag recognition and kinetic scrolling.
 *
 * The runtime defers primary presses over ordinary descendants until release, cancels activation after movement,
 * and scrolls this viewport after vertical movement exceeds its logical drag threshold.
 * Descendant [PointerCaptureNode] controls retain exclusive dragging; [HorizontalDragNode] controls arbitrate by direction.
 * Gesture ownership and momentum end on input reset, removal, unplacement, detachment, or close.
 */
public interface DragScrollNode : PointerInputNode {
    /**
     * Content-drag recognition and inertia policy for this viewport.
     */
    public val dragScrollOptions: DragScrollOptions
        get() = DragScrollOptions.Default

    /**
     * Stable identity of the current navigation state, compared by reference.
     *
     * Replace it when this retained viewport switches to another scroll state or navigation context.
     * The runtime then cancels pending taps, drags, and momentum from the previous context.
     */
    public val dragScrollIdentity: Any
        get() = this

    /**
     * Whether the current content has a scrollable vertical range.
     */
    public val canDragScroll: Boolean

    /**
     * Changes the vertical offset by [delta] logical pixels and returns the displacement actually consumed.
     *
     * Positive values advance toward the end of the content. Clamp at content boundaries and invalidate the affected geometry.
     * This callback runs under the owner-thread input boundary for pointer motion and explicitly timed kinetic frames.
     * It may request additional rows under the same rules as ordinary scroll input.
     */
    public fun scrollByDrag(delta: Double): Double
}
