package dev.s7a.strata.runtime.platform

/**
 * Identifies the single JavaScript execution agent hosting this runtime instance.
 */
internal actual object PlatformThreads {
    /**
     * Returns a stable identity for the current execution thread.
     */
    actual fun current(): Any = this

    /**
     * Identifies the JavaScript execution agent in ownership diagnostics.
     */
    actual fun name(owner: Any): String {
        check(owner === this) { "Unknown JavaScript owner." }
        return "JavaScript"
    }
}
