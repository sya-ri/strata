package dev.s7a.strata.internal.platform

/**
 * Holds the context of a synchronous evaluation independently on each JVM thread or JavaScript agent.
 * Null releases the retained context; asynchronous work must explicitly install its own context.
 */
internal expect class EvaluationContext<T : Any>() {
    /**
     * The active value, or null outside evaluation.
     * Prefer [withValue] for scoped evaluation; paired operation guards manage this value across their enter and leave calls.
     */
    var current: T?
}

/**
 * Installs [value] for [action] and restores its caller's context on every return or failure.
 */
internal fun <T : Any, R> EvaluationContext<T>.withValue(
    value: T,
    action: () -> R,
): R {
    val previous = current
    current = value
    return try {
        action()
    } finally {
        current = previous
    }
}
