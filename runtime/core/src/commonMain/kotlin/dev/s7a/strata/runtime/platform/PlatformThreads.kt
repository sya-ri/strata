package dev.s7a.strata.runtime.platform

/**
 * Supplies opaque owner-thread identities without exposing JVM thread types.
 */
internal expect object PlatformThreads {
    /**
     * Returns a stable identity for the current execution thread.
     */
    fun current(): Any

    /**
     * Returns the diagnostic label of a previously captured thread identity.
     */
    fun name(owner: Any): String
}
