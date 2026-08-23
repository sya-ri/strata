package dev.s7a.strata.component

/**
 * Caller-owned owner-thread navigation state shared by one VirtualList and optional independent Scrollbar.
 *
 * Index and key jumps issued before attachment remain pending and are applied when a list model attaches.
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
     * Claims this state for one retained list and applies any pending jump.
     */
    internal fun attach(next: VirtualListController<K>) {
        checkThread()
        check(controller == null) { "VirtualListState is already attached to a list." }
        controller = next
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

    /**
     * Requests deferred row reconstruction from the attached viewport.
     */
    internal fun refresh() {
        checkThread()
        controller?.refresh()
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
