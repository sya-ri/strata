package dev.s7a.strata.runtime

/**
 * Queue membership for one binding permanently owned by one source-cutoff queue.
 * Only that queue reads or writes [pendingInQueue], under its [PendingBindingQueue.monitor].
 * Implementations store this bit directly without application callbacks or value comparisons.
 */
internal interface PendingBindingTarget {
    /**
     * Whether the owner queue currently retains this binding in its pending selection.
     * Detached captured selections do not own this bit, so callbacks can enqueue the following cutoff.
     */
    var pendingInQueue: Boolean
}
