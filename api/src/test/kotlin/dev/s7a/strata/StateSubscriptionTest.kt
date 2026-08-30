package dev.s7a.strata

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.lang.Thread.State as ThreadState

/**
 * Verifies the subscription handle's terminal state machine and close ownership.
 */
internal class StateSubscriptionTest {
    @Test
    fun retainedCloseActionSharesExactlyOnceOutcomeWithoutHoldingTheInitialSnapshot() {
        val expected = IllegalStateException("close")
        var closes = 0
        val initial = StateSnapshot(StateRevision(1), Any())
        val subscription =
            StateSubscription(initial) {
                closes += 1
                throw expected
            }
        val close = subscription.retainCloseAction()
        assertSame(expected, assertThrows(IllegalStateException::class.java) { close() })
        assertSame(expected, assertThrows(IllegalStateException::class.java) { subscription.close() })
        assertEquals(1, closes)
        val controllerField = subscription.javaClass.getDeclaredField("closeController")
        controllerField.isAccessible = true
        val controller = controllerField.get(subscription)
        assertTrue(controller.javaClass.declaredFields.none { field -> field.type == StateSnapshot::class.java })
        assertTrue(controller.javaClass.declaredFields.none { field -> field.type == StateSubscription::class.java })
    }

    @Test
    fun successfulCloseRunsActionOnceAndConcurrentCallersWait() {
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val actionCount = AtomicInteger()
        val handle =
            StateSubscription(StateSnapshot(StateRevision(0), Unit)) {
                actionCount.incrementAndGet()
                actionStarted.countDown()
                releaseAction.await()
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { handle.close() }
            assertTrue(actionStarted.await(2, TimeUnit.SECONDS))
            val secondThread = CompletableFuture<Thread>()
            val second =
                executor.submit {
                    secondThread.complete(Thread.currentThread())
                    handle.close()
                }
            assertTrue(awaitWaiting(secondThread.get(2, TimeUnit.SECONDS)))
            releaseAction.countDown()

            first.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            handle.close()

            assertEquals(1, actionCount.get())
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun failedCloseRunsActionOnceAndSharesTheExactFailure() {
        val expected = IllegalStateException("close")
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        val actionCount = AtomicInteger()
        val handle =
            StateSubscription(StateSnapshot(StateRevision(0), Unit)) {
                actionCount.incrementAndGet()
                actionStarted.countDown()
                releaseAction.await()
                throw expected
            }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<Throwable?> { runCatching { handle.close() }.exceptionOrNull() }
            assertTrue(actionStarted.await(2, TimeUnit.SECONDS))
            val second = executor.submit<Throwable?> { runCatching { handle.close() }.exceptionOrNull() }
            releaseAction.countDown()

            val firstFailure = first.get(2, TimeUnit.SECONDS)
            val secondFailure = second.get(2, TimeUnit.SECONDS)
            val laterFailure = assertThrows(IllegalStateException::class.java) { handle.close() }

            assertSame(expected, firstFailure)
            assertSame(expected, secondFailure)
            assertSame(expected, laterFailure)
            assertEquals(1, actionCount.get())
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun reentrantCloseFromCleanupFailsWithoutReportingSuccess() {
        lateinit var handle: StateSubscription<Unit>
        handle = StateSubscription(StateSnapshot(StateRevision(0), Unit)) { handle.close() }

        val firstFailure = assertThrows(IllegalStateException::class.java) { handle.close() }
        val secondFailure = assertThrows(IllegalStateException::class.java) { handle.close() }

        assertSame(firstFailure, secondFailure)
    }

    @Test
    fun cleanupCannotSwallowReentrantCloseFailureAndReturnNormally() {
        lateinit var handle: StateSubscription<Unit>
        handle =
            StateSubscription(StateSnapshot(StateRevision(0), Unit)) {
                runCatching { handle.close() }
            }

        val failure = assertThrows(IllegalStateException::class.java) { handle.close() }

        assertSame(failure, assertThrows(IllegalStateException::class.java) { handle.close() })
    }

    @Test
    fun cleanupFailureIsSuppressedBehindRetainedReentryFailure() {
        val cleanupFailure = IllegalArgumentException("cleanup")
        lateinit var handle: StateSubscription<Unit>
        handle =
            StateSubscription(StateSnapshot(StateRevision(0), Unit)) {
                runCatching { handle.close() }
                throw cleanupFailure
            }

        val failure = assertThrows(IllegalStateException::class.java) { handle.close() }

        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertSame(failure, assertThrows(IllegalStateException::class.java) { handle.close() })
    }

    private fun awaitWaiting(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (thread.state == ThreadState.WAITING) {
                return true
            }
            Thread.onSpinWait()
        }
        return thread.state == ThreadState.WAITING
    }
}
