package dev.s7a.strata.runtime.platform

import kotlin.coroutines.CoroutineContext

/**
 * Describes dynamically scoped coroutine-generation state for the JavaScript owner dispatcher.
 */
internal actual interface PlatformThreadContextElement<S> : CoroutineContext.Element {
    /**
     * Installs this generation and returns the preceding thread-local state.
     */
    actual fun updateThreadContext(context: CoroutineContext): S

    /**
     * Restores the preceding thread-local state after a segment.
     */
    actual fun restoreThreadContext(
        context: CoroutineContext,
        oldState: S,
    )
}
