package dev.s7a.strata.runtime.platform

import java.util.concurrent.locks.ReentrantLock

/**

 * Preserves JVM callback synchronization with a reentrant lock.

 */
internal actual class PlatformLock actual constructor() {
    private val delegate = ReentrantLock()

    /**
     * Acquires this lock on the current thread.
     */
    actual fun lock() {
        delegate.lock()
    }

    /**
     * Releases one acquisition owned by the current thread.
     */
    actual fun unlock() {
        delegate.unlock()
    }

    /**
     * Reports whether the current thread owns this lock.
     */
    actual fun isHeldByCurrentThread(): Boolean = delegate.isHeldByCurrentThread

    /**
     * Creates a condition tied to this lock's ownership.
     */
    actual fun newCondition(): PlatformCondition = PlatformCondition(delegate.newCondition())
}
