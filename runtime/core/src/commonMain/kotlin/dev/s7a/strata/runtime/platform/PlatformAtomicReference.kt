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
}
