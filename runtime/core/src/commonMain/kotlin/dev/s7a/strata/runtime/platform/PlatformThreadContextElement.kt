package dev.s7a.strata.runtime.platform

import kotlin.coroutines.CoroutineContext

/**
 * Restores a retained coroutine generation around each execution segment.
 */
internal expect interface PlatformThreadContextElement<S> : CoroutineContext.Element {
    /**
     * Installs this generation and returns the preceding thread-local state.
     */
    fun updateThreadContext(context: CoroutineContext): S

    /**
     * Restores the preceding thread-local state after a segment.
     */
    fun restoreThreadContext(
        context: CoroutineContext,
        oldState: S,
    )
}
