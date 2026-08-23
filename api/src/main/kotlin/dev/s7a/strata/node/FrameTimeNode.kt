package dev.s7a.strata.node

import dev.s7a.strata.runtime.FrameTime

/**
 * Retained capability notified once for each explicitly timed host frame before cache reuse is decided.
 *
 * Implementations compare [FrameTime] with retained animation state and invalidate only phases whose discrete output changed.
 * The callback is owner-thread confined, must not mutate session state, and must tolerate equal timestamps.
 */
public fun interface FrameTimeNode {
    /**
     * Observes the next host timestamp and conditionally invalidates changed output.
     *
     * @param time monotonic timestamp from the owning host clock.
     */
    public fun onFrame(time: FrameTime)
}
