package dev.s7a.strata.node

/**
 * Retained capability that makes its logical component a keyboard and text-input focus target.
 *
 * Focus transitions are synchronous on the owning tree thread.
 * Implementations may invalidate Paint or Semantics, but geometry invalidation during a completed layout transition is unsupported and fails the following phase precondition.
 */
public interface FocusTargetNode {
    /**
     * Whether this node currently permits its logical component to receive focus.
     */
    public val acceptsFocus: Boolean

    /**
     * Whether this node requests focus when no retained component currently owns it after layout.
     */
    public val requestsInitialFocus: Boolean
        get() = false

    /**
     * Applies one distinct focus transition.
     *
     * @param focused true after acquisition and false before loss.
     * @throws Throwable when component focus behavior fails; the retained runtime preserves the exact failure as primary.
     */
    public fun onFocusChanged(focused: Boolean)
}
