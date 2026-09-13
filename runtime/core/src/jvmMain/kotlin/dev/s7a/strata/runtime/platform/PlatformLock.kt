package dev.s7a.strata.runtime.platform

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Serializes callback queue access with a reentrant JVM lock.
 */
internal actual class PlatformLock actual constructor() {
    private val delegate = ReentrantLock()

    /**
     * Returns the action's result and releases the JVM lock on every exit.
     */
    actual fun <T> withLock(action: () -> T): T = delegate.withLock(action)
}
