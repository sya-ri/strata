package dev.s7a.strata.state

import java.util.concurrent.locks.ReentrantLock

/**
 * The atomic result of subscribing to a [StateSource].
 *
 * A source establishes observation before returning this handle.
 * It may therefore invoke the callback before [StateSource.subscribe] returns; [initialSnapshot] remains the observation at the subscription linearization point.
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
        private val monitor = ReentrantLock()
        private val completed = monitor.newCondition()
        private var state: CloseState = CloseState.Open
        private var reentrantFailure: Throwable? = null

        override fun close() {
            val runAction = claimClose(Thread.currentThread())
            if (runAction.not()) return
            val failure = runCatching(closeAction).exceptionOrNull()
            val terminalFailure = finishClose(failure)
            terminalFailure?.let { thrown -> throw thrown }
        }

        private fun claimClose(currentThread: Thread): Boolean {
            var runAction: Boolean? = null
            var observedFailure: Throwable? = null
            monitor.lock()
            try {
                while (runAction == null && observedFailure == null) {
                    when (val current = state) {
                        CloseState.Open -> {
                            state = CloseState.Closing(currentThread)
                            runAction = true
                        }

                        is CloseState.Closing -> {
                            if (current.owner === currentThread) {
                                val failure =
                                    reentrantFailure
                                        ?: IllegalStateException("State subscription close re-entered its cleanup action.")
                                reentrantFailure = failure
                                throw failure
                            }
                            completed.awaitUninterruptibly()
                        }

                        CloseState.Closed -> {
                            runAction = false
                        }

                        is CloseState.Failed -> {
                            observedFailure = current.failure
                        }
                    }
                }
            } finally {
                monitor.unlock()
            }
            observedFailure?.let { failure -> throw failure }
            return runAction == true
        }

        private fun finishClose(failure: Throwable?): Throwable? {
            monitor.lock()
            try {
                val reentrant = reentrantFailure
                if (reentrant != null && failure != null && reentrant !== failure) {
                    reentrant.addSuppressed(failure)
                }
                val terminalFailure = reentrant ?: failure
                state = if (terminalFailure == null) CloseState.Closed else CloseState.Failed(terminalFailure)
                completed.signalAll()
                return terminalFailure
            } finally {
                monitor.unlock()
            }
        }

        private sealed interface CloseState {
            data object Open : CloseState

            data class Closing(
                val owner: Thread,
            ) : CloseState

            data object Closed : CloseState

            data class Failed(
                val failure: Throwable,
            ) : CloseState
        }
    }
}
