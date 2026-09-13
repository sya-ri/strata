package dev.s7a.strata.internal.platform

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * Runs [action] exactly once while holding [lock], releasing ownership on every return or failure.
 */
@OptIn(ExperimentalContracts::class)
internal inline fun <T> synchronized(
    lock: PlatformLock,
    action: () -> T,
): T {
    contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
    lock.lock()
    return try {
        action()
    } finally {
        lock.unlock()
    }
}
