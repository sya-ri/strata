package dev.s7a.strata.node

/**
 * Optional session-attachment lifetime for resources that cannot remain active while their retained tree is detached.
 *
 * Ordinary [LifecycleNode] ownership is unchanged: a session keeps its nodes across detachment and only performs ordinary lifecycle cleanup when a node leaves the tree.
 * These additional callbacks run on the tree owner thread, never dispose a node, and must not mutate session state.
 * Implementations must tolerate an already active initial attachment and an already suspended terminal cleanup.
 * Callback failures trigger best-effort cleanup of the remaining nodes and preserve the original failure.
 */
public interface SessionAttachmentNode : LifecycleNode {
    /**
     * Ensures attachment-scoped observation is active when the owning session initially attaches or reattaches its retained tree.
     *
     * New nodes acquire their initial resources in [LifecycleNode.attach], including nodes inserted by ordinary reconciliation.
     * Ordinary reconciliation does not invoke this callback.
     * This callback resumes suspended resources and must not acquire duplicates when [LifecycleNode.attach] has already activated them.
     */
    public fun sessionAttached()

    /**
     * Immediately disables attachment-scoped observation while retaining node identity and authoritative external sources.
     *
     * Implementations clear their active resource references before invoking fallible cleanup and must remain safe for later ordinary lifecycle cleanup.
     */
    public fun sessionDetached()
}
