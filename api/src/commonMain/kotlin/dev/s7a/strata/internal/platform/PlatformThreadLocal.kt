package dev.s7a.strata.internal.platform

/**
 * Stores a nullable value independently for each execution thread.
 */
internal expect class PlatformThreadLocal<T>() {
    /**
     * Returns the current thread's value or null before its first assignment.
     */
    fun get(): T?

    /**
     * Replaces the current thread's retained value.
     */
    fun set(value: T)

    /**
     * Releases the current thread's retained value.
     */
    fun remove()
}
