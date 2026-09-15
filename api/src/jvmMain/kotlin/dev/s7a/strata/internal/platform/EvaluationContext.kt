package dev.s7a.strata.internal.platform

/**
 * Isolates synchronous evaluation state per JVM thread and releases captures when evaluation ends.
 */
internal actual class EvaluationContext<T : Any> actual constructor() {
    private val storage = ThreadLocal<T>()

    actual var current: T?
        get() = storage.get()
        set(value) {
            if (value == null) storage.remove() else storage.set(value)
        }
}
