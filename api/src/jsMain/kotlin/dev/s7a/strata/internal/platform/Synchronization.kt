package dev.s7a.strata.internal.platform

/**
 * Runs [action] directly because synchronous JavaScript operations complete on one agent.
 */
internal actual inline fun <T> synchronized(
    lock: Any,
    action: () -> T,
): T = action()
