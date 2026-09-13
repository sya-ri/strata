package dev.s7a.strata.internal.platform

/**
 * Identifies the single JavaScript execution agent hosting this runtime instance.
 */
internal actual object PlatformThreads {
    /**
     * Returns a stable identity for the current execution thread.
     */
    actual fun current(): Any = this
}
