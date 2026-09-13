package dev.s7a.strata.runtime.platform

/**
 * Enforces single-agent condition invariants; synchronous JavaScript cannot have a concurrent close waiter.
 * Reentrant close is rejected by the caller before it reaches a wait.
 */
internal actual class PlatformCondition(
    private val lock: PlatformLock,
) {
    /**
     * Waits without interruption until signalled, atomically releasing and reacquiring the owning lock.
     */
    actual fun awaitUninterruptibly(): Unit = error("A JavaScript execution agent cannot await a concurrent synchronous owner.")

    /**
     * Wakes all concurrent waiters while holding the owning lock.
     */
    actual fun signalAll() {
        check(lock.isHeldByCurrentThread()) { "Signalling requires lock ownership." }
    }
}
