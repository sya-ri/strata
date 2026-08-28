package dev.s7a.strata.node

/**
 * Retained capability that publishes external observations at the start of every timed or untimed session frame.
 *
 * Callbacks run on the tree owner thread before reconciliation, measurement, or frame-cache lookup and must not mutate session state.
 * Every participating entry is captured before any entry is committed, so publication triggered by a commit cannot enter a later entry in that frame.
 * A callback failure poisons the retained tree and preserves the original exception through best-effort cleanup.
 */
public interface FrameCutoffNode {
    /**
     * Captures pending observations without invoking observers, comparing caller values, or committing visible state.
     *
     * Implementations may retain one transaction-local snapshot until [commitFrameState], in addition to their committed and newest pending observations.
     * Cleanup must release a captured snapshot even when another entry's capture fails.
     */
    public fun captureFrameState()

    /**
     * Publishes only the captured observation and invalidates changed retained phases.
     *
     * Notifications arriving after [captureFrameState] remain pending until the next frame.
     * This method must release its transaction-local snapshot before invoking fallible caller code.
     */
    public fun commitFrameState()
}
