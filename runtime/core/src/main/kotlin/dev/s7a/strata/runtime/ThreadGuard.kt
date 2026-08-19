package dev.s7a.strata.runtime

/**
 * Verifies that retained runtime operations run on one owning thread.
 *
 * @property owner the thread captured when this guard was created.
 */
internal class ThreadGuard private constructor(
    private val owner: Thread,
) {
    /**
     * Fails when the current thread is not the owner.
     *
     * @throws IllegalStateException when called from another thread.
     */
    internal fun check() {
        check(Thread.currentThread() === owner) {
            "This runtime object is owned by ${owner.name}."
        }
    }

    /**
     * Returns whether the current thread owns this guard without throwing.
     */
    internal fun isOwnerThread(): Boolean = Thread.currentThread() === owner

    /**
     * Factory methods for thread guards.
     */
    internal companion object {
        /**
         * Creates a guard owned by the current thread.
         */
        internal fun currentThread(): ThreadGuard = ThreadGuard(Thread.currentThread())
    }
}
