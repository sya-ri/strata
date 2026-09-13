package dev.s7a.strata.runtime.platform

/**
 * Synchronous JavaScript callbacks already run to completion on one agent.
 */
internal actual class PlatformLock actual constructor() {
    /**
     * Runs the action immediately on the current agent and propagates its result or failure.
     */
    actual fun <T> withLock(action: () -> T): T = action()
}
