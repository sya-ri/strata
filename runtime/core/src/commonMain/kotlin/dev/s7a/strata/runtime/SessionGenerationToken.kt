package dev.s7a.strata.runtime

import kotlinx.coroutines.Runnable
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Identifies one attachment and carries its generation through coroutine execution and failure delivery.
 * Each execution segment restores its caller's generation before returning; retirement is visible to JVM workers.
 */
internal expect class SessionGenerationToken() : AbstractCoroutineContextElement {
    /**
     * Whether this attachment still accepts work; the owner retires it before cancellation.
     */
    var active: Boolean

    /**
     * Runs a synchronous failure handler under this generation and restores its caller on every exit.
     */
    fun run(action: () -> Unit)

    /**
     * Resumes one coroutine segment, using native JVM context hooks or explicit JavaScript context installation.
     */
    fun resume(block: Runnable)

    /**
     * Identifies the generation element and exposes only the generation executing in the current context.
     */
    companion object Key : CoroutineContext.Key<SessionGenerationToken> {
        /**
         * The executing generation, or null outside a coroutine segment or failure handler.
         */
        val current: SessionGenerationToken?
    }
}
