package dev.s7a.strata.runtime.platform

/**
 * Serializes callback queue access on JVM; JavaScript runs each synchronous action on its current agent.
 */
internal expect class PlatformLock() {
    /**
     * Runs [action] once under reentrant ownership and releases ownership on return or failure.
     */
    fun <T> withLock(action: () -> T): T
}
