package dev.s7a.strata.internal.platform

/**

 * Tracks synchronous lock ownership in one JavaScript execution agent.

 */
internal actual class PlatformLock actual constructor() {
    private var depth = 0

    /**
     * Acquires this lock on the current thread.
     */
    actual fun lock() {
        depth += 1
    }

    /**
     * Releases one acquisition owned by the current thread.
     */
    actual fun unlock() {
        check(0 < depth) { "Unlock requires ownership." }
        depth -= 1
    }

    /**
     * Reports whether the current thread owns this lock.
     */
    actual fun isHeldByCurrentThread(): Boolean = 0 < depth

    /**
     * Creates a condition tied to this lock's ownership.
     */
    actual fun newCondition(): PlatformCondition = PlatformCondition(this)
}
