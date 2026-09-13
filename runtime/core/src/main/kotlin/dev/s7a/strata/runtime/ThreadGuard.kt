package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.PlatformThreads

/**
 * Verifies that retained runtime operations run on one owning thread.
 *
 * Construction captures the current thread.
 */
internal class ThreadGuard {
    private val owner = PlatformThreads.current()

    /**
     * Fails when the current thread is not the owner.
     *
     * @throws IllegalStateException when called from another thread.
     */
    internal fun check() {
        check(PlatformThreads.current() === owner) {
            "This runtime object is owned by ${PlatformThreads.name(owner)}."
        }
    }

    /**
     * Returns whether the current thread owns this guard without throwing.
     */
    internal fun isOwnerThread(): Boolean = PlatformThreads.current() === owner
}
