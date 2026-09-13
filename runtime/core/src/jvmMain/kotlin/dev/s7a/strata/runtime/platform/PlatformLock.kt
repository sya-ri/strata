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
}
