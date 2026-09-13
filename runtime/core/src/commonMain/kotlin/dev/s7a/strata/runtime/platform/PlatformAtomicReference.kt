package dev.s7a.strata.runtime.platform

/**
 * Provides atomic owner transfer without invoking user equality.
 */
internal expect class PlatformAtomicReference<V>(
    initial: V,
) {
    /**
     * Returns the current value with platform synchronization.
     */
    fun get(): V

    /**
     * Replaces the current value with platform synchronization.
     */
    fun set(value: V)

    /**
     * Atomically replaces the value and returns its previous identity.
     */
    fun getAndSet(value: V): V

    /**
     * Replaces the value only when its current identity matches expected.
     */
    fun compareAndSet(
        expected: V,
        value: V,
    ): Boolean
}
