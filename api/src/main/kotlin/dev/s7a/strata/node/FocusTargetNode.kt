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
     * Whether this accepting focus target edits text and requires the platform's text-input mode.
     *
     * Keyboard shortcuts and passive text observers keep the default false value.
     * The runtime samples this capability after layout and focus acquisition on the owning tree thread.
     * Changes must invalidate retained presentation so the next frame can reconcile the capability.
     * An adapter may use the resulting focus interval to enable native input methods without retaining this node.
     */
    public val requiresTextInput: Boolean
        get() = false

    /**
     * Applies one distinct focus transition.
     *
     * @param focused true after acquisition and false before loss.
     * @throws Throwable when component focus behavior fails; the retained runtime preserves the exact failure as primary.
     */
    public fun onFocusChanged(focused: Boolean)
}
