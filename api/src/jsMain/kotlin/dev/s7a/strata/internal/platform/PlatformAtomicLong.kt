package dev.s7a.strata.internal.platform

/**
 * Implements atomic operations within one JavaScript execution agent without suspension.
 */
internal actual class PlatformAtomicLong actual constructor(
    initial: Long,
) {
    private var current = initial

    /**
     * Returns the current value with platform synchronization.
     */
    actual fun get(): Long = current

    /**
     * Replaces the current value with platform synchronization.
     */
    actual fun set(value: Long) {
        current = value
    }

    /**
     * Replaces the value only when its current identity matches expected.
     */
    actual fun compareAndSet(
        expected: Long,
        value: Long,
    ): Boolean {
        if (current != expected) return false
        current = value
        return true
    }

    /**
     * Atomically applies an update; JVM callers must permit retrying the operation.
     */
    actual fun updateAndGet(update: (Long) -> Long): Long {
        val next = update(current)
        current = next
        return next
    }
}
