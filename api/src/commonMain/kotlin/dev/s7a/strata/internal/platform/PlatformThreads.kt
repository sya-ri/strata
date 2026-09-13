package dev.s7a.strata.internal.platform

/**
 * Supplies opaque owner-thread identities without exposing JVM thread types.
 */
internal expect object PlatformThreads {
    /**
     * Returns a stable identity for the current execution thread.
     */
    fun current(): Any
}
