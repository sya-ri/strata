package dev.s7a.strata.internal.platform

/**
 * Lock-bound condition used to await a concurrently claimed terminal cleanup.
 */
internal expect class PlatformCondition {
    /**
     * Waits without interruption until signalled, atomically releasing and reacquiring the owning lock.
     */
    fun awaitUninterruptibly()

    /**
     * Wakes all concurrent waiters while holding the owning lock.
     */
    fun signalAll()
}
