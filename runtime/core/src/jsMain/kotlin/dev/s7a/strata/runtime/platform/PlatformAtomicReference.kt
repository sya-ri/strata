package dev.s7a.strata.runtime.platform

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
     * Replaces the current value with platform synchronization.
     */
    actual fun set(value: V) {
        current = value
    }
}
