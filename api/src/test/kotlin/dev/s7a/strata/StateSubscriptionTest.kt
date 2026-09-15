package dev.s7a.strata

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun successfulCloseRunsOnceAndInterruptedConcurrentCallersWait() {
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
                executor.submit<Boolean> {
                    secondThread.complete(Thread.currentThread())
                    handle.close()
                    Thread.currentThread().isInterrupted
                }
            val waiter = secondThread.get(2, TimeUnit.SECONDS)
            assertTrue(awaitBlocked(waiter))
            waiter.interrupt()
            assertFalse(second.isDone)
            releaseAction.countDown()

            first.get(2, TimeUnit.SECONDS)
            assertTrue(second.get(2, TimeUnit.SECONDS))
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
            val secondThread = CompletableFuture<Thread>()
            val second =
                executor.submit<Throwable?> {
                    secondThread.complete(Thread.currentThread())
                    runCatching { handle.close() }.exceptionOrNull()
                }
            assertTrue(awaitBlocked(secondThread.get(2, TimeUnit.SECONDS)))
            releaseAction.countDown()

            val firstFailure = first.get(2, TimeUnit.SECONDS)
            val secondFailure = second.get(2, TimeUnit.SECONDS)
            val laterFailure = assertThrows(IllegalStateException::class.java) { handle.close() }

            assertSame(expected, firstFailure)
            assertSame(expected, secondFailure)
            assertSame(expected, laterFailure)
            assertEquals(1, actionCount.get())
        } finally {
            releaseAction.countDown()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    private fun awaitBlocked(thread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (thread.state == ThreadState.BLOCKED) {
                return true
            }
            Thread.onSpinWait()
        }
        return thread.state == ThreadState.BLOCKED
    }
}
