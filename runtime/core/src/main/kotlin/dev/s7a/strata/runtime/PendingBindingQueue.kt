package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.synchronized

/**
 * Current revision targets for one owner's two-phase source cutoff.
 * Binding callbacks use [monitor] for snapshot updates and enqueue/remove; capture freezes the complete selection under that same lock.
 * [order] compares only owner-maintained traversal order, and [capture] callbacks cannot compare values or run application code.
 * At most one pending and one captured reference per current binding is retained; owner cleanup calls [clear].
 * Each nonempty cutoff detaches its pending list; removal rebuilds after its tracked peak exceeds twice current membership.
 * Empty frames therefore retain neither historical entries nor a historical pending-list peak.
 */
internal class PendingBindingQueue<T : PendingBindingTarget>(
    private val order: Comparator<T>,
) {
    /**
     * Shared lock for queue membership and each participating binding's revision snapshots.
     */
    val monitor = Any()
    private var pending = ArrayList<T>()
    private var pendingPeak = 0
    private var captured: List<T> = emptyList()

    /**
     * Marks an accepted revision while the binding already holds [monitor].
     * Runtime binding identities use ordinary reference equality without application equality code.
     */
    fun enqueue(binding: T) {
        if (binding.pendingInQueue) return
        pending.add(binding)
        binding.pendingInQueue = true
        if (pendingPeak < pending.size) pendingPeak = pending.size
    }

    /**
     * Removes queued work after initial-snapshot coalescing or disable, while holding [monitor].
     * An already captured target remains valid only until owner commit or terminal [clear].
     */
    fun remove(binding: T) {
        if (binding.pendingInQueue.not()) return
        val index = pending.indexOfFirst { it === binding }
        check(0 <= index) { "A pending binding belongs to another queue." }
        pending.removeAt(index)
        binding.pendingInQueue = false
        if (pending.isEmpty() || pending.size.toLong() * 2 < pendingPeak.toLong()) {
            pending = ArrayList(pending)
            pendingPeak = pending.size
        }
    }

    /**
     * Freezes all selected snapshots before releasing the lock or allowing any value comparison.
     * The owner pairs this with [takeCaptured], or [clear] after a failed cutoff.
     */
    fun capture(freeze: (T) -> Unit) {
        synchronized(monitor) {
            captured =
                if (pending.isEmpty()) {
                    emptyList()
                } else {
                    pending.also { selected ->
                        pending = ArrayList()
                        pendingPeak = 0
                        selected.forEach { it.pendingInQueue = false }
                        selected.sortWith(order)
                    }
                }
            captured.forEach(freeze)
        }
    }

    /**
     * Transfers the detached current selection to owner-thread commit and releases the queue's references.
     * Callbacks can independently enqueue the following cutoff while these targets commit.
     */
    fun takeCaptured(): List<T> = captured.also { captured = emptyList() }

    /**
     * Drops every queued and captured reference during owner-confined terminal cleanup.
     * Bindings must also disable their callbacks before cleanup completes.
     */
    fun clear() {
        synchronized(monitor) {
            pending.forEach { it.pendingInQueue = false }
            pending = ArrayList()
            pendingPeak = 0
            captured = emptyList()
        }
    }
}
