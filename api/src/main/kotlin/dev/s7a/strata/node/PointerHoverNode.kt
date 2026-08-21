package dev.s7a.strata.node

/**
 * Node capability that observes whether the latest pointer move hits its accumulated laid-out bounds.
 *
 * The runtime invokes this callback on the owning tree thread for every placed capable node before ordinary move-event dispatch.
 * It also supplies `false` when an owning session clears hover during detachment.
 * Implementations decide whether repeated equal values have observable work.
 */
public interface PointerHoverNode {
    /**
     * Observes the current hit state for the latest pointer transition.
     *
     * @param hovered whether the pointer is inside this node's half-open accumulated bounds.
     * @throws Throwable when the implementation cannot apply the transition; the owning tree preserves the exact failure as primary while poisoning and cleaning retained ownership.
     */
    public fun onPointerHover(hovered: Boolean)
}
