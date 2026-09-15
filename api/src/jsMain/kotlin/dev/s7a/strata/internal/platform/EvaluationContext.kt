package dev.s7a.strata.internal.platform

/**
 * Stores only the synchronous evaluation active in this JavaScript agent.
 */
internal actual class EvaluationContext<T : Any> actual constructor() {
    actual var current: T? = null
}
