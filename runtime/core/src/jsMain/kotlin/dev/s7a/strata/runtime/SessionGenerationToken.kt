package dev.s7a.strata.runtime

import kotlinx.coroutines.Runnable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Installs only the generation executing synchronously on the JavaScript owner dispatcher.
 */
internal actual class SessionGenerationToken actual constructor() : AbstractCoroutineContextElement(Key) {
    actual var active: Boolean = true

    /**
     * Installs this generation for synchronous execution and restores the caller even after failure.
     */
    actual fun run(action: () -> Unit) {
        val previous = executing
        executing = this
        try {
            action()
        } finally {
            executing = previous
        }
    }

    /**
     * Installs this generation for one owner-dispatcher continuation segment.
     */
    actual fun resume(block: Runnable) {
        run { block.run() }
    }

    /**
     * Identifies this context element and tracks only the segment executing in this JavaScript agent.
     */
    actual companion object Key : CoroutineContext.Key<SessionGenerationToken> {
        private var executing: SessionGenerationToken? = null
        actual val current: SessionGenerationToken? get() = executing
    }
}
