package dev.s7a.strata.runtime.platform

/**
 * Uses the JVM thread itself as its stable owner identity.
 */
internal actual object PlatformThreads {
    /**
     * Returns a stable identity for the current execution thread.
     */
    actual fun current(): Any = Thread.currentThread()

    /**
     * Preserves the JVM owner thread name in access diagnostics.
     */
    actual fun name(owner: Any): String = (owner as Thread).name
}
