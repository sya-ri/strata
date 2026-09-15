package dev.s7a.strata.runtime

import kotlinx.coroutines.Runnable
import kotlinx.coroutines.ThreadContextElement
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Installs this attachment through the JVM coroutine hooks, including worker sections entered with withContext.
 */
internal actual class SessionGenerationToken actual constructor() :
    AbstractCoroutineContextElement(Key),
    ThreadContextElement<SessionGenerationToken?> {
        @Volatile
        actual var active: Boolean = true

        /**
         * Installs this generation for synchronous failure delivery and restores the caller even after failure.
         */
        actual fun run(action: () -> Unit) {
            val previous = updateThreadContext(EmptyCoroutineContext)
            try {
                action()
            } finally {
                restoreThreadContext(EmptyCoroutineContext, previous)
            }
        }

        /**
         * Runs the continuation; kotlinx.coroutines installs this element through its native thread context hooks.
         */
        actual fun resume(block: Runnable) {
            block.run()
        }

        override fun updateThreadContext(context: CoroutineContext): SessionGenerationToken? = storage.get().also { storage.set(this) }

        override fun restoreThreadContext(
            context: CoroutineContext,
            oldState: SessionGenerationToken?,
        ) {
            if (oldState == null) storage.remove() else storage.set(oldState)
        }

        /**
         * Identifies this context element and isolates the executing generation per JVM thread.
         */
        actual companion object Key : CoroutineContext.Key<SessionGenerationToken> {
            private val storage = ThreadLocal<SessionGenerationToken>()
            actual val current: SessionGenerationToken? get() = storage.get()
        }
    }
