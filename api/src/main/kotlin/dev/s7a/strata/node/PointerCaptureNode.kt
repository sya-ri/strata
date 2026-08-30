package dev.s7a.strata.node

import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent

/**
 * Opts a pointer handler into exclusive delivery after it consumes a press.
 *
 * The owner-thread runtime retains at most one captured node and its starting button per tree.
 * A [PointerEvent.Press] returning [InputResult.Consumed] acquires capture only while no other handler owns it, then invokes [onPointerCaptureAcquired].
 * Captured moves and drags or releases for that same button bypass bounds and ancestor clips, use the latest committed local coordinates without clamping, and stop propagation even when the handler returns [InputResult.Ignored].
 * Other buttons, scrolling, and hit-tested hover observation retain their ordinary dispatch behavior.
 * A matching release clears ownership before delivery and does not invoke [onPointerCaptureCancelled].
 * Removal, replacement, unplacement, input reset, detachment, close, and failure instead clear ownership before invoking cancellation once.
 * Callback replacement on an otherwise retained node does not cancel capture.
 * Implementations remain externally described and runtime-owned like ordinary [PointerInputNode] capabilities; no registration or component-specific dispatch is required.
 * Callback failures escape unchanged through the active tree operation, which still attempts remaining cleanup.
 */
public interface PointerCaptureNode : PointerInputNode {
    /**
     * Confirms that this node acquired one captured gesture on the owning tree thread.
     *
     * The consumed press callback has returned, the runtime has installed its capture reference, and no captured drag, move, release, or cancellation has run yet.
     * A consumed press does not imply acquisition when another node already owns capture, so implementations that retain gesture state should begin it here.
     * The default implementation retains no state.
     * If this callback fails, terminal tree cleanup clears ownership before invoking [onPointerCaptureCancelled].
     *
     * @param button the button whose consumed press acquired capture.
     * @throws Throwable when gesture initialization fails; the tree preserves that failure while cancelling the installed capture during cleanup.
     */
    public fun onPointerCaptureAcquired(button: PointerButton): Unit = Unit

    /**
     * Cancels one unfinished captured gesture on the owning tree thread.
     *
     * The runtime has already removed its capture reference before this callback runs and invokes it before this node is disposed.
     * This notification is not a synthetic release and must not assume that a pointer remains inside the node.
     *
     * @param button the button whose consumed press began the cancelled gesture.
     * @throws Throwable when application cancellation fails; the original failure remains primary unless an earlier failure already exists, and remaining cleanup is still attempted.
     */
    public fun onPointerCaptureCancelled(button: PointerButton)
}
