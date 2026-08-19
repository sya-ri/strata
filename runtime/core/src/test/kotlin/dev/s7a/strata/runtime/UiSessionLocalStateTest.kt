package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.properties.ReadWriteProperty

/**
 * Verifies local session state declaration, equality, dirty tracking, and content guards.
 */
internal class UiSessionLocalStateTest {
    @Test
    fun sameValueDoesNotRebuildAndBatchedMutationsRebuildOnce() {
        val probe = TestProbe()
        val holder = LocalHolder<Int>()
        var contentCalls = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls += 1
                probe.root(emptyList())
            }
        holder.delegate = session.state(0)

        holder.value = 1
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals(1, contentCalls)

        holder.value = 1
        session.frame(Constraints.fixed(2, 1))
        assertEquals(1, contentCalls)

        holder.value = 2
        holder.value = 3
        holder.value = 4
        session.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentCalls)
        session.close()
    }

    @Test
    fun createdAndDetachedStatesAllowReadsAndWrites() {
        val probe = TestProbe()
        val holder = LocalHolder<Int>()
        var contentValue = -1
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentValue = holder.value
                probe.root(emptyList())
            }
        holder.delegate = session.state(1)
        assertEquals(1, holder.value)
        holder.value = 2
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentValue)

        session.detach()
        holder.value = 3
        assertEquals(3, holder.value)
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        assertEquals(3, contentValue)
        session.close()
    }

    @Test
    fun declarationsAfterAttachAndDuringContentAreRejected() {
        val probe = TestProbe()
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        session.attach()
        assertThrows(IllegalStateException::class.java) { session.state(1) }
        session.close()

        lateinit var declarationSession: UiSession
        declarationSession =
            UiSession(TestOwnerDispatcher()) {
                declarationSession.state(1)
                probe.root(emptyList())
            }
        assertThrows(IllegalStateException::class.java) { declarationSession.attach() }
        assertTrue(declarationSession.lifecycleState is UiSessionState.Failed)
        declarationSession.close()
    }

    @Test
    fun contentMutationAndLifecycleReentrancyPoisonTheSession() {
        val probe = TestProbe()
        val mutationHolder = LocalHolder<Int>()
        lateinit var mutationSession: UiSession
        mutationSession =
            UiSession(TestOwnerDispatcher()) {
                mutationHolder.value = 1
                probe.root(emptyList())
            }
        mutationHolder.delegate = mutationSession.state(0)
        assertThrows(IllegalStateException::class.java) { mutationSession.attach() }
        assertTrue(mutationSession.lifecycleState is UiSessionState.Failed)
        mutationSession.close()

        lateinit var lifecycleSession: UiSession
        lifecycleSession =
            UiSession(TestOwnerDispatcher()) {
                lifecycleSession.detach()
                probe.root(emptyList())
            }
        assertThrows(IllegalStateException::class.java) { lifecycleSession.attach() }
        assertTrue(lifecycleSession.lifecycleState is UiSessionState.Failed)
        lifecycleSession.close()
    }

    @Test
    fun failedAndClosedSessionsRejectDelegateAccess() {
        val closedHolder = LocalHolder<Int>()
        val closedSession = UiSession(TestOwnerDispatcher()) { TestProbe().root(emptyList()) }
        closedHolder.delegate = closedSession.state(0)
        closedSession.close()
        assertThrows(IllegalStateException::class.java) { closedHolder.value }
        assertThrows(IllegalStateException::class.java) { closedHolder.value = 1 }

        val failure = IllegalStateException("content")
        val failedHolder = LocalHolder<Int>()
        val failedSession = UiSession(TestOwnerDispatcher()) { throw failure }
        failedHolder.delegate = failedSession.state(0)
        assertSame(failure, assertThrows(IllegalStateException::class.java) { failedSession.attach() })
        assertThrows(IllegalStateException::class.java) { failedHolder.value }
        assertThrows(IllegalStateException::class.java) { failedHolder.value = 1 }
        failedSession.close()
    }

    @Test
    fun equalityGuardsAllSessionActionsAndThrowingEqualityPreservesTheOldValue() {
        val dispatcher = TestOwnerDispatcher()
        val probe = TestProbe()
        val holder = LocalHolder<Any?>()
        val other = LocalHolder<Int>()
        var sameReadRejected = false
        var sameWriteRejected = false
        var otherReadRejected = false
        var otherWriteRejected = false
        var closeRejected = false
        var frameRejected = false
        var declarationRejected = false
        var bindRejected = false
        lateinit var session: UiSession
        session =
            UiSession(dispatcher) {
                probe.root(emptyList())
            }
        holder.delegate =
            session.state(
                EqualityValue {
                    sameReadRejected = runCatching { holder.value }.isFailure
                    sameWriteRejected = runCatching { holder.value = 2 }.isFailure
                    otherReadRejected = runCatching { other.value }.isFailure
                    otherWriteRejected = runCatching { other.value = 2 }.isFailure
                    closeRejected = runCatching { session.close() }.isFailure
                    frameRejected = runCatching { session.frame(Constraints.fixed(2, 1)) }.isFailure
                    declarationRejected = runCatching { session.state(1) }.isFailure
                    bindRejected =
                        runCatching {
                            session.bind(
                                StateSource<Int> { _ ->
                                    StateSubscription(StateSnapshot(StateRevision(1), 1)) {}
                                },
                            )
                        }.isFailure
                },
            )
        other.delegate = session.state(0)
        session.attach()
        holder.value = EqualityValue { }
        assertTrue(sameReadRejected)
        assertTrue(sameWriteRejected)
        assertTrue(otherReadRejected)
        assertTrue(otherWriteRejected)
        assertTrue(closeRejected)
        assertTrue(frameRejected)
        assertTrue(declarationRejected)
        assertTrue(bindRejected)
        assertEquals(UiSessionState.Attached, session.lifecycleState)
        session.close()
        throwingEqualityPreservesOldValue()
    }

    private fun throwingEqualityPreservesOldValue() {
        val throwingDispatcher = TestOwnerDispatcher()
        val throwingProbe = TestProbe()
        val throwingHolder = LocalHolder<Any?>()
        var contentCalls = 0
        val equalityFailure = IllegalArgumentException("equals")
        var shouldThrow = true
        val oldValue = EqualityValue { if (shouldThrow) throw equalityFailure }
        val throwingSession =
            UiSession(throwingDispatcher) {
                contentCalls += 1
                throwingProbe.root(emptyList())
            }
        throwingHolder.delegate = throwingSession.state(oldValue)
        throwingSession.attach()
        throwingSession.frame(Constraints.fixed(2, 1))
        assertSame(equalityFailure, assertThrows(IllegalArgumentException::class.java) { throwingHolder.value = 1 })
        assertSame(oldValue, throwingHolder.value)
        throwingSession.frame(Constraints.fixed(2, 1))
        assertEquals(1, contentCalls)
        shouldThrow = false
        throwingHolder.value = 1
        throwingSession.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentCalls)
        assertTrue(throwingProbe.events.none { event -> event is TestProbe.Event.Dispose })
        throwingSession.close()
    }

    private class LocalHolder<T> {
        lateinit var delegate: ReadWriteProperty<Any?, T>

        var value: T
            get() = delegate.getValue(this, LocalHolder<T>::value)
            set(next) = delegate.setValue(this, LocalHolder<T>::value, next)
    }

    private class EqualityValue(
        private val action: () -> Unit,
    ) {
        override fun equals(other: Any?): Boolean {
            action()
            return other is EqualityValue
        }

        override fun hashCode(): Int = 0
    }
}
