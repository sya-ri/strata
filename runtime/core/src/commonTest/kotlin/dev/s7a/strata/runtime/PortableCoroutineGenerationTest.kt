package dev.s7a.strata.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies suspension, retired generations, and task failures through the JVM and JavaScript dispatch bridges.
 */
internal class PortableCoroutineGenerationTest {
    @Test
    fun interleavedSessionsKeepTheirGenerationAcrossSuspensionAndRestoreTheCaller() {
        val dispatcher = QueuedDispatcher()
        val first = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        val second = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        var firstValue by first.state(0)
        var secondValue by second.state(0)
        val order = mutableListOf<Int>()
        try {
            first.attach()
            second.attach()
            first.screenScope.launch {
                order.add(1)
                firstValue = 1
                assertFailsWith<IllegalStateException> { secondValue }
                yield()
                order.add(3)
                firstValue += 1
                assertFailsWith<IllegalStateException> { secondValue = 99 }
            }
            second.screenScope.launch {
                order.add(2)
                secondValue = 1
                assertFailsWith<IllegalStateException> { firstValue }
                yield()
                order.add(4)
                secondValue += 1
                assertFailsWith<IllegalStateException> { firstValue = 99 }
            }
            dispatcher.drain()
            assertEquals(listOf(1, 2, 3, 4), order)
            assertEquals(2, firstValue)
            assertEquals(2, secondValue)
            firstValue = 3
            secondValue = 3
            assertEquals(3, firstValue)
            assertEquals(3, secondValue)
        } finally {
            first.close()
            second.close()
            dispatcher.drain()
        }
    }

    @Test
    fun retiredFinallyCannotMutateTheReattachedSession() {
        val dispatcher = QueuedDispatcher()
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        var value by session.state(0)
        var finalized = 0
        session.attach()
        session.screenScope.launch {
            value = 1
            yield()
            try {
                awaitCancellation()
            } finally {
                finalized += 1
                assertFailsWith<IllegalStateException> { value = 99 }
            }
        }
        dispatcher.drain()
        assertEquals(1, value)
        session.detach()
        session.attach()
        session.screenScope.launch { value = 2 }
        dispatcher.drain()
        assertEquals(1, finalized)
        assertEquals(2, value)
        session.close()
        dispatcher.drain()
        assertEquals(1, finalized)
    }

    @Test
    fun resumedTaskFailurePreservesIdentityAndAllowsAnExplicitContinueDecision() {
        val dispatcher = QueuedDispatcher()
        val expected = IllegalStateException("Resumed task failure")
        val failures = ArrayList<Throwable>()
        val session =
            UiSession(dispatcher, { failure ->
                failures.add(failure)
                UiTaskFailureDecision.Continue
            }) { TestProbe().root(emptyList()) }
        session.attach()
        session.screenScope.launch {
            yield()
            throw expected
        }
        dispatcher.drain()
        assertSame(expected, failures.single())
        var nextRan = false
        session.screenScope.launch { nextRan = true }
        dispatcher.drain()
        assertEquals(true, nextRan)
        session.close()
    }

    /**
     * Owns a bounded synchronous test queue; draining releases every executed continuation.
     */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            pending.addLast(block)
        }

        /**
         * Drives owner continuations and fails deterministically if a test never settles.
         */
        fun drain() {
            var executed = 0
            while (pending.isNotEmpty()) {
                check(executed < 100) { "The owner queue did not settle." }
                pending.removeFirst().run()
                executed += 1
            }
        }
    }
}
