package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.currentThread
import dev.s7a.strata.runtime.platform.threadName

/**
 * Verifies that retained runtime operations run on one owning thread.
 *
 * Construction captures the current thread.
 */
internal class ThreadGuard {
    private val owner = currentThread()

    /**
     * Fails when the current thread is not the owner.
     *
     * @throws IllegalStateException when called from another thread.
     */
    internal fun check() {
        check(currentThread() === owner) {
            "This runtime object is owned by ${threadName(owner)}."
        }
    }

    /**
     * Returns whether the current thread owns this guard without throwing.
     */
    internal fun isOwnerThread(): Boolean = currentThread() === owner
}
