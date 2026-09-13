package dev.s7a.strata.internal.platform

import java.util.concurrent.atomic.AtomicLong

/**
 * Preserves JVM atomic visibility and compare-and-set behavior.
 */
internal actual class PlatformAtomicLong actual constructor(
    initial: Long,
) {
    private val delegate = AtomicLong(initial)

    /**
     * Returns the current value with platform synchronization.
     */
    actual fun get(): Long = delegate.get()

    /**
     * Replaces the current value with platform synchronization.
     */
    actual fun set(value: Long) {
        delegate.set(value)
    }

    /**
     * Replaces the value only when its current identity matches expected.
     */
    actual fun compareAndSet(
        expected: Long,
        value: Long,
    ): Boolean = delegate.compareAndSet(expected, value)

    /**
     * Atomically applies an update; JVM callers must permit retrying the operation.
     */
    actual fun updateAndGet(update: (Long) -> Long): Long = delegate.updateAndGet(update)
}
