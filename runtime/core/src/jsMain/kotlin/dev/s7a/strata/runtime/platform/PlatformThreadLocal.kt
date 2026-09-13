package dev.s7a.strata.runtime.platform

/**
 * Holds dynamically scoped state in the current JavaScript execution agent.
 */
internal actual class PlatformThreadLocal<T> actual constructor() {
    private var value: T? = null

    /**
     * Returns the current thread's value or null before its first assignment.
     */
    actual fun get(): T? = value

    /**
     * Replaces the current thread's retained value.
     */
    actual fun set(value: T) {
        this.value = value
    }

    /**
     * Releases the current thread's retained value.
     */
    actual fun remove() {
        value = null
    }
}
