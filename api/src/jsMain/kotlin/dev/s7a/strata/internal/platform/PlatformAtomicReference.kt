package dev.s7a.strata.internal.platform

/**
 * Implements atomic operations within one JavaScript execution agent without suspension.
 */
internal actual class PlatformAtomicReference<V> actual constructor(
    initial: V,
) {
    private var current = initial

    /**
     * Returns the current value with platform synchronization.
     */
    actual fun get(): V = current

    /**
     * Atomically replaces the value and returns its previous identity.
     */
    actual fun getAndSet(value: V): V {
        val previous = current
        current = value
        return previous
    }

    /**
     * Replaces the current value with platform synchronization.
     */
    actual fun set(value: V) {
        current = value
    }

    /**
     * Replaces the value only when its current identity matches expected.
     */
    actual fun compareAndSet(
        expected: V,
        value: V,
    ): Boolean {
        if (current !== expected) return false
        current = value
        return true
    }
}
