package dev.s7a.strata.component

/**
 * Caller-owned owner-thread navigation state shared by one VirtualList and optional independent Scrollbar.
 *
 * Index and key jumps issued before attachment remain pending and are applied when a list model attaches.
 * A refresh issued before attachment also remains pending and is applied before pending navigation.
 * A key jump returns false when the attached model can prove the key is absent; before attachment it is accepted as pending.
 * Only one retained VirtualList may attach at a time, while any number of Scrollbars may observe [scrollState].
 *
 * @param K stable item key type.
 * @property scrollState position and geometry shared with independent scrollbars.
 * @param initialIndex optional initial zero-based item target.
 */
public class VirtualListState<K : Any>(
    public val scrollState: ScrollState = ScrollState(),
    initialIndex: Int? = null,
) {
    private val ownerThread = Thread.currentThread()
    private var controller: VirtualListController<K>? = null
    private var refreshPending = false
    private var pending: VirtualListJump<K>? =
        initialIndex?.let { index ->
            require(0 <= index) { "VirtualList initial index must be non-negative." }
            VirtualListJump.Index(index)
        }

    /**
     * Moves the target item to the viewport start or retains the request until attachment.
     *
     * @param index non-negative absolute item index.
     * @return false only when an attached model proves the index is outside its range.
     */
    public fun jumpToIndex(index: Int): Boolean {
        checkThread()
        require(0 <= index) { "VirtualList jump index must be non-negative." }
        return request(VirtualListJump.Index(index))
    }

    /**
     * Moves the keyed item to the viewport start or retains the request until attachment.
     *
     * @param key stable item key.
     * @return false only when an attached model cannot resolve [key].
     */
    public fun jumpToKey(key: K): Boolean {
        checkThread()
        return request(VirtualListJump.Key(key))
    }

    /**
     * Invalidates materialized rows after a caller-owned source or row-presentation mutation.
     *
     * Complete the mutation before calling this method on the state creator thread.
     * A dynamic VirtualList then samples its count exactly once, validates the new range, reconstructs visible rows even when the count is unchanged, and preserves the last visible stable-key anchor when possible.
     * A call without an attached list is coalesced and applied before pending navigation when the next list attaches.
     * Count sampling and key-index validation failures propagate without replacing the attached list's last valid count and geometry, and the caller may correct the source and retry.
     *
     * @throws IllegalStateException when called from a thread other than the state creator thread.
     * @throws IllegalArgumentException when the attached dynamic source reports an invalid count or key index.
     */
    public fun refresh() {
        checkThread()
        val current = controller
        if (current == null) {
            refreshPending = true
            return
        }
        current.refresh()
    }

    /**
     * Claims this state for one retained list and applies any pending jump.
     */
    internal fun attach(next: VirtualListController<K>) {
        checkThread()
        check(controller == null) { "VirtualListState is already attached to a list." }
        controller = next
        if (refreshPending) {
            next.refresh()
            refreshPending = false
        }
        pending?.let { target ->
            if (next.jump(target)) pending = null
        }
    }

    /**
     * Releases the exact retained list claim without disturbing Scrollbar observers.
     */
    internal fun detach(current: VirtualListController<K>) {
        checkThread()
        if (controller === current) controller = null
    }

    private fun request(target: VirtualListJump<K>): Boolean {
        val current = controller
        if (current == null) {
            pending = target
            return true
        }
        val applied = current.jump(target)
        if (applied) pending = null
        return applied
    }

    private fun checkThread() {
        check(Thread.currentThread() === ownerThread) { "VirtualListState requires its creator thread." }
    }
}
