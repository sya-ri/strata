package dev.s7a.strata.internal.platform

/**
 * Provides atomic owner transfer without invoking user equality.
 */
internal expect class PlatformAtomicLong(
    initial: Long = 0L,
) {
    /**
     * Returns the current value with platform synchronization.
     */
    fun get(): Long

    /**
     * Replaces the current value with platform synchronization.
     */
    fun set(value: Long)

    /**
     * Replaces the value only when its current identity matches expected.
     */
    fun compareAndSet(
        expected: Long,
        value: Long,
    ): Boolean

    /**
     * Atomically applies an update; JVM callers must permit retrying the operation.
     */
    fun updateAndGet(update: (Long) -> Long): Long
}
