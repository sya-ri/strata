package dev.s7a.strata

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSubscription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies exactly-once cleanup results and reentrant close behavior on JVM and JavaScript.
 */
internal class PortableStateSubscriptionTest {
    @Test
    fun retainedCloseActionSharesSuccessfulAndFailedOutcomes() {
        for (expected in listOf(null, IllegalStateException("close"))) {
            var closes = 0
            val handle =
                StateSubscription(StateSnapshot(StateRevision(0), Unit)) {
                    closes += 1
                    expected?.let { throw it }
                }
            val close = handle.retainCloseAction()

            assertSame(expected, runCatching(close).exceptionOrNull())
            assertSame(expected, runCatching(handle::close).exceptionOrNull())
            assertSame(expected, runCatching(close).exceptionOrNull())
            assertEquals(1, closes)
        }
    }

    @Test
    fun reentrantCloseFromCleanupFailsWithoutReportingSuccess() {
        lateinit var handle: StateSubscription<Unit>
        handle = StateSubscription(StateSnapshot(StateRevision(0), Unit)) { handle.close() }

        val firstFailure = assertFailsWith<IllegalStateException> { handle.close() }
        val secondFailure = assertFailsWith<IllegalStateException> { handle.close() }

        assertSame(firstFailure, secondFailure)
    }

    @Test
    fun cleanupCannotSwallowReentrantCloseFailureAndReturnNormally() {
        lateinit var handle: StateSubscription<Unit>
        handle =
            StateSubscription(StateSnapshot(StateRevision(0), Unit)) {
                runCatching { handle.close() }
            }

        val failure = assertFailsWith<IllegalStateException> { handle.close() }

        assertSame(failure, assertFailsWith<IllegalStateException> { handle.close() })
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

        val failure = assertFailsWith<IllegalStateException> { handle.close() }

        assertEquals(listOf(cleanupFailure), failure.suppressedExceptions)
        assertSame(failure, assertFailsWith<IllegalStateException> { handle.close() })
    }
}
