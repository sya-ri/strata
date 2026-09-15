package dev.s7a.strata.state

import dev.s7a.strata.internal.platform.synchronized
import kotlin.jvm.JvmSynthetic

/**
 * The atomic result of subscribing to a [StateSource].
 *
 * A source establishes observation before returning this handle.
 * It may therefore invoke the callback before [StateSource.subscribe] returns; [StateSubscription.initialSnapshot] remains the observation at the subscription linearization point.
 * Closing is idempotent and thread-safe.
 * Concurrent callers wait for the one close action to finish and observe the same failure when it fails.
 *
 * @param T the observed value type.
 * @property initialSnapshot the value observed when the subscription was established.
 * @param closeAction closes the source-owned observation and does not return successfully until the source can start no further callback for it.
 */
public class StateSubscription<out T> public constructor(
    public val initialSnapshot: StateSnapshot<T>,
    closeAction: () -> Unit,
) : AutoCloseable {
    private val closeController = CloseController(closeAction)

    /**
     * Closes this observation exactly once.
     *
     * The source cleanup callback runs at most once and runs synchronously on the first caller.
     * Other callers wait for it to finish.
     * A cleanup failure without re-entry is propagated unchanged by this and every later call.
     * Re-entry from the cleanup callback is rejected instead of deadlocking.
     * The re-entry failure becomes primary even when cleanup catches it, and any distinct failure subsequently thrown by cleanup is suppressed on it.
     * A successful return guarantees that the source can start no further callback for this subscription, although a callback already in progress may finish.
     *
     * @throws Throwable when source cleanup fails.
     */
    override fun close() {
        closeController.close()
    }

    /**
     * Retains the shared close operation without retaining this subscription's initial value.
     *
     * Image bindings use this after transferring the initial observation so obsolete pixels do not remain reachable through a cleanup handle.
     * The returned operation is thread-safe, shares this subscription's exactly-once result, and retains only the source-owned cleanup action and close state.
     *
     * @return a cleanup operation with the same failure and concurrency behavior as [close].
     */
    @JvmSynthetic
    internal fun retainCloseAction(): () -> Unit = closeController::close

    private class CloseController(
        private val closeAction: () -> Unit,
    ) : AutoCloseable {
        private val monitor = Any()
        private var state: CloseState = CloseState.Open
        private var reentrantFailure: Throwable? = null

        override fun close() {
            // Only close operations use this lock; holding it through cleanup makes concurrent callers wait for completion.
            val failure =
                synchronized(monitor) {
                    when (val current = state) {
                        CloseState.Open -> {
                            state = CloseState.Closing
                            val cleanupFailure = runCatching(closeAction).exceptionOrNull()
                            val reentrant = reentrantFailure
                            if (reentrant != null && cleanupFailure != null && reentrant !== cleanupFailure) {
                                reentrant.addSuppressed(cleanupFailure)
                            }
                            val terminalFailure = reentrant ?: cleanupFailure
                            state = if (terminalFailure == null) CloseState.Closed else CloseState.Failed(terminalFailure)
                            terminalFailure
                        }

                        CloseState.Closing -> {
                            // Another thread cannot enter until cleanup finishes; this caller must be reentrant.
                            val reentrant = reentrantFailure ?: IllegalStateException("State subscription close re-entered its cleanup action.")
                            reentrantFailure = reentrant
                            reentrant
                        }

                        CloseState.Closed -> {
                            null
                        }

                        is CloseState.Failed -> {
                            current.failure
                        }
                    }
                }
            failure?.let { throw it }
        }

        private sealed interface CloseState {
            data object Open : CloseState

            data object Closing : CloseState

            data object Closed : CloseState

            data class Failed(
                val failure: Throwable,
            ) : CloseState
        }
    }
}
