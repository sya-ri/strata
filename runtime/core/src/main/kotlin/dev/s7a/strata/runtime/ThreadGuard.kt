package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.PlatformThreads

/**
 * Verifies that retained runtime operations run on one owning thread.
 *
 * @property owner the thread captured when this guard was created.
 */
internal class ThreadGuard private constructor(
    private val owner: Any,
) {
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

    /**
     * Factory methods for thread guards.
     */
    internal companion object {
        /**
         * Creates a guard owned by the current thread.
         */
        internal fun currentThread(): ThreadGuard = ThreadGuard(PlatformThreads.current())
    }
}
