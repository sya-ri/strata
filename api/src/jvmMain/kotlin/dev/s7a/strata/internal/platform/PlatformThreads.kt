package dev.s7a.strata.internal.platform

/**
 * Uses the JVM thread itself as its stable owner identity.
 */
internal actual object PlatformThreads {
    /**
     * Returns a stable identity for the current execution thread.
     */
    actual fun current(): Any = Thread.currentThread()
}
