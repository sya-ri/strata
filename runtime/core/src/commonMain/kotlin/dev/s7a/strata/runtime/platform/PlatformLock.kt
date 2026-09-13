package dev.s7a.strata.runtime.platform

/**
 * Reentrant lock protecting any-thread callback queues on JVM and synchronous operations on JavaScript.
 */
internal expect class PlatformLock() {
    /**
     * Acquires this lock on the current thread.
     */
    fun lock()

    /**
     * Releases one acquisition owned by the current thread.
     */
    fun unlock()
}
