package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.state.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies public state dependency tracking through the retained session's real frame and cleanup paths.
 */
internal class UiSessionObservedStateTest {
    @Test
    fun unobservedStateWritesAreRejectedDuringPaintAndTerminalCleanup() {
        val state = mutableStateOf(0)
        val probe = TestProbe()
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        session.attach()
        val node = probe.nodeForTag(TestProbe.ProbeId("root"))
        node.onPaint = {
            assertThrows(IllegalStateException::class.java) { state.value = 1 }
        }
        node.onDispose = {
            assertThrows(IllegalStateException::class.java) { state.value = 2 }
        }
        session.frame(Constraints.fixed(2, 1))
        session.close()
        assertEquals(0, state.value)
        state.value = 3
        assertEquals(3, state.value)
    }

    @Test
    fun conditionalReadsReplaceDependenciesAndBatchChanges() {
        val branch = mutableStateOf(false)
        val first = mutableStateOf(10)
        val second = mutableStateOf(20)
        val probe = TestProbe()
        var calls = 0
        var shown = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                calls += 1
                shown = if (branch.value) first.value else second.value
                probe.root(emptyList())
            }
        session.use {
            session.attach()
            session.frame(Constraints.fixed(2, 1))
            assertEquals(20, shown)
            first.value = 11
            second.value = 20
            session.frame(Constraints.fixed(2, 1))
            assertEquals(1, calls)
            branch.value = true
            first.value = 12
            first.value = 13
            session.frame(Constraints.fixed(2, 1))
            assertEquals(13, shown)
            assertEquals(2, calls)
            second.value = 21
            session.frame(Constraints.fixed(2, 1))
            assertEquals(2, calls)
            branch.value = false
            session.frame(Constraints.fixed(2, 1))
            assertEquals(21, shown)
            assertEquals(3, calls)
        }
    }

    @Test
    fun whenReevaluatesAfterDetachAndCallerStateOutlivesClose() {
        val branch = mutableStateOf(Phase.Loading)
        val probe = TestProbe()
        var shown = ""
        val session =
            UiSession(TestOwnerDispatcher()) {
                shown =
                    when (branch.value) {
                        Phase.Loading -> "Loading"
                        Phase.Ready -> "Ready"
                        Phase.Failed -> "Failed"
                    }
                probe.root(emptyList())
            }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals("Loading", shown)
        session.detach()
        branch.value = Phase.Ready
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals("Ready", shown)
        session.close()
        branch.value = Phase.Failed
        assertEquals(Phase.Failed, branch.value)
    }

    @Test
    fun evenAnUnreadStateCannotBeWrittenDuringContent() {
        val state = mutableStateOf(0)
        val probe = TestProbe()
        val session =
            UiSession(TestOwnerDispatcher()) {
                state.value = 1
                probe.root(emptyList())
            }
        assertThrows(IllegalStateException::class.java, session::attach)
        assertEquals(0, state.value)
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        session.close()
        state.value = 2
        assertEquals(2, state.value)
    }

    @Test
    fun failureReleasesDependenciesAndPreservesFailureIdentity() {
        val state = mutableStateOf(false)
        val failure = IllegalArgumentException("Content failed")
        val probe = TestProbe()
        val session =
            UiSession(TestOwnerDispatcher()) {
                if (state.value) throw failure
                probe.root(emptyList())
            }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        state.value = true
        assertSame(failure, assertThrows(IllegalArgumentException::class.java) { session.frame(Constraints.fixed(2, 1)) })
        state.value = false
        session.close()
    }

    @Test
    fun oneStateInvalidatesBothScreensAndClosingOnePreservesTheOther() {
        val state = mutableStateOf(0)
        val probe = TestProbe()
        val otherProbe = TestProbe()
        var firstValue = -1
        var secondValue = -1
        val first =
            UiSession(TestOwnerDispatcher()) {
                firstValue = state.value
                probe.root(emptyList())
            }
        val second =
            UiSession(TestOwnerDispatcher()) {
                secondValue = state.value
                otherProbe.root(emptyList())
            }
        first.attach()
        second.attach()
        state.value = 1
        first.frame(Constraints.fixed(2, 1))
        second.frame(Constraints.fixed(2, 1))
        assertEquals(1, firstValue)
        assertEquals(1, secondValue)
        first.close()
        state.value = 2
        second.frame(Constraints.fixed(2, 1))
        assertEquals(2, secondValue)
        second.close()
    }

    private enum class Phase { Loading, Ready, Failed }
}
