package dev.s7a.strata.node

/**
 * A node that owns resources across attachment, detachment, and disposal.
 *
 * The runtime invokes lifecycle callbacks on the owning tree thread.
 * Attachment is parent-first.
 * Cleanup is reverse-sibling descendant-first and attempts each callback at most once.
 * It calls detach after every attempted attach, including a failed attempt.
 * It disposes bound nodes whose attach callback was never reached.
 * Cleanup continues after callback failures and suppresses later distinct failures on the primary failure.
 * A lifecycle callback failure during an active tree operation is propagated unchanged and poisons the tree.
 * A cleanup callback failure during close leaves the tree closed after cleanup attempts.
 */
public interface LifecycleNode {
    /**
     * Acquires resources after the node is inserted into a tree.
     *
     * The runtime attempts this callback at most once for one node lifetime.
     * A failure still counts as an attempted attach, so cleanup may call [detach] before [dispose].
     */
    public fun attach()

    /**
     * Releases attachment-scoped resources while retaining runtime identity and binding ownership through the subsequent dispose attempt.
     *
     * The runtime invokes this callback on the owning tree thread at most once and only after [attach] was attempted.
     * Cleanup invokes it before [dispose], even when [attach] failed.
     */
    public fun detach()

    /**
     * Permanently releases resources owned by this node.
     *
     * The runtime invokes this callback on the owning tree thread at most once for every bound node.
     * This includes nodes whose [attach] callback was never reached.
     * Cleanup continues with other nodes when this callback throws.
     */
    public fun dispose()
}
