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
