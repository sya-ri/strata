package dev.s7a.strata.node

import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent

/**
 * A node that receives pointer events in its local coordinate space.
 *
 * The runtime visits the deepest and latest-painted hit node first.
 * Returning [InputResult.Ignored] allows bubbling to continue, while [InputResult.Consumed] stops dispatch.
 */
public interface PointerInputNode {
    /**
     * Handles a pointer event after layout hit testing.
     *
     * The event position is tested against half-open accumulated bounds.
     * A child may receive an event outside its parent's bounds when the child itself is placed there.
     * The runtime does not add implicit parent clipping.
     *
     * The event position remains in tree coordinates.
     * A drag displacement is inverse-scaled into this node's local logical units, while scroll displacement retains its adapter-normalized wheel units.
     *
     * @param event the tree-positioned event with receiver-local drag displacement.
     * @param localPosition the event position relative to this node's top-left corner.
     * @return whether dispatch should stop.
     */
    public fun onPointerEvent(
        event: PointerEvent,
        localPosition: IntOffset,
    ): InputResult
}
