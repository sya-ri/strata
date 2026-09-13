package dev.s7a.strata.internal.platform

import java.util.concurrent.locks.Condition

/**

 * Preserves uninterruptible JVM close coordination.

 */
internal actual class PlatformCondition(
    private val delegate: Condition,
) {
    /**
     * Waits without interruption until signalled, atomically releasing and reacquiring the owning lock.
     */
    actual fun awaitUninterruptibly() {
        delegate.awaitUninterruptibly()
    }

    /**
     * Wakes all concurrent waiters while holding the owning lock.
     */
    actual fun signalAll() {
        delegate.signalAll()
    }
}
