package dev.s7a.strata.runtime.platform

/**
 * Delegates nullable thread storage to the JVM, releasing captures on removal.
 */
internal actual class PlatformThreadLocal<T> actual constructor() {
    private val delegate = ThreadLocal<T>()

    /**
     * Returns the current thread's value or null before its first assignment.
     */
    actual fun get(): T? = delegate.get()

    /**
     * Replaces the current thread's retained value.
     */
    actual fun set(value: T) {
        delegate.set(value)
    }

    /**
     * Releases the current thread's retained value.
     */
    actual fun remove() {
        delegate.remove()
    }
}
