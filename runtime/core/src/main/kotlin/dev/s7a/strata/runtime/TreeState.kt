package dev.s7a.strata.runtime

/**
 * Lifecycle state of a retained UI tree.
 *
 * The state transitions from [Active] to [Poisoned] after a post-validation or pipeline failure.
 * It transitions from [Active] to [Closed] when close begins.
 * Non-active states reject operational work.
 * [Poisoned] permits close, which transitions it to [Closed].
 * [Closed] is terminal and later close calls are no-ops.
 */
public sealed interface TreeState {
    /**
     * The tree accepts operational work.
     */
    public data object Active : TreeState

    /**
     * A pipeline or lifecycle failure has made the tree unusable except for close.
     *
     * The retained root and node ownership have already been cleared, while cleanup attempts may still have run or reported failures.
     * Calling close transitions this state to [Closed].
     */
    public data object Poisoned : TreeState

    /**
     * The tree has completed close processing.
     *
     * Cleanup may have reported a failure, but the state remains closed and later close calls are no-ops.
     */
    public data object Closed : TreeState
}
