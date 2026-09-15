package dev.s7a.strata.runtime.platform

import kotlin.synchronized as jvmSynchronized

/**
 * Runs [action] under [lock]'s monitor, releasing it on every return or failure.
 */
internal actual inline fun <T> synchronized(
    lock: Any,
    action: () -> T,
): T = jvmSynchronized(lock, action)
