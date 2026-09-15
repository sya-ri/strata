package dev.s7a.strata.internal.platform

/**
 * Runs [action] under the reentrant JVM monitor of [lock], or directly on the current JavaScript agent.
 */
internal expect inline fun <T> synchronized(
    lock: Any,
    action: () -> T,
): T
