package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.properties.ReadWriteProperty

/**
 * Verifies legal lifecycle transitions, owner confinement, failure identity, and cleanup order.
 */
internal class UiSessionLifecycleFailureTest {
    @Test
    fun legalAndIllegalTransitionsAndWrongThreadAreDeterministic() {
        val probe = TestProbe()
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        assertThrows(IllegalStateException::class.java) { session.detach() }
        assertThrows(IllegalStateException::class.java) {
            session.dispatchPointer(PointerEvent.Move(IntOffset(0, 0)))
        }
        session.attach()
        assertThrows(IllegalStateException::class.java) { session.attach() }
        session.detach()
        assertThrows(IllegalStateException::class.java) { session.frame(Constraints.fixed(2, 1)) }
        session.attach()

        val task =
            FutureTask<Throwable?> {
                runCatching { session.frame(Constraints.fixed(2, 1)) }.exceptionOrNull()
            }
        val thread = Thread(task)
        thread.start()
        assertTrue(task.get(5, TimeUnit.SECONDS) is IllegalStateException)
        session.close()
    }

    @Test
    fun failureCloseTransitionsFailedToClosedWithoutRepeatingCleanup() {
        val primary = IllegalArgumentException("content")
        val session = UiSession(TestOwnerDispatcher()) { throw primary }
        assertSame(primary, assertThrows(IllegalArgumentException::class.java) { session.attach() })
        val failed = assertInstanceOf(UiSessionState.Failed::class.java, session.lifecycleState)
        assertSame(primary, failed.cause)
        session.close()
        session.close()
        assertEquals(UiSessionState.Closed, session.lifecycleState)
    }

    @Test
    fun contentAndPipelineFailuresPreservePrimaryIdentity() {
        val contentFailure = IllegalStateException("content failure")
        val contentSession = UiSession(TestOwnerDispatcher()) { throw contentFailure }
        assertSame(contentFailure, assertThrows(IllegalStateException::class.java) { contentSession.attach() })
        contentSession.close()

        val measureFailure = IllegalStateException("measure failure")
        val measureProbe = TestProbe(failingMeasureTag = TestProbe.ProbeId("root"), measureFailure = measureFailure)
        val measureSession = UiSession(TestOwnerDispatcher()) { measureProbe.root(emptyList()) }
        measureSession.attach()
        assertSame(measureFailure, assertThrows(IllegalStateException::class.java) { measureSession.frame(Constraints.fixed(2, 1)) })
        measureSession.close()

        val paintFailure = IllegalStateException("paint failure")
        val paintProbe = TestProbe(failingPaintTag = TestProbe.ProbeId("root"), paintFailure = paintFailure)
        val paintSession = UiSession(TestOwnerDispatcher()) { paintProbe.root(emptyList()) }
        paintSession.attach()
        assertSame(paintFailure, assertThrows(IllegalStateException::class.java) { paintSession.frame(Constraints.fixed(2, 1)) })
        paintSession.close()

        val inputFailure = IllegalStateException("input failure")
        val inputProbe = TestProbe(failingInputTag = TestProbe.ProbeId("root"), inputFailure = inputFailure)
        val inputSession = UiSession(TestOwnerDispatcher()) { inputProbe.root(emptyList()) }
        inputSession.attach()
        inputSession.frame(Constraints.fixed(2, 1))
        assertSame(
            inputFailure,
            assertThrows(IllegalStateException::class.java) {
                inputSession.dispatchPointer(PointerEvent.Move(IntOffset(0, 0)))
            },
        )
        inputSession.close()
    }

    @Test
    fun contentSeesAttachedButRejectsDeclarationsBindingsMutationScopeAndLifecycle() {
        val dispatcher = TestOwnerDispatcher()
        val probe = TestProbe()
        val holder = Holder<Int>()
        var observedAttached = false
        var declarationRejected = false
        var bindRejected = false
        var mutationRejected = false
        var scopeRejected = false
        var lifecycleRejected = false
        lateinit var session: UiSession
        val source =
            StateSource<Int> { _ ->
                StateSubscription(StateSnapshot(StateRevision(1), 1)) {}
            }
        session =
            UiSession(dispatcher) {
                observedAttached = session.lifecycleState === UiSessionState.Attached
                declarationRejected = runCatching { session.state(1) }.isFailure
                bindRejected = runCatching { session.bind(source) }.isFailure
                mutationRejected = runCatching { holder.value = 1 }.isFailure
                scopeRejected = runCatching { session.screenScope.launch {} }.isFailure
                lifecycleRejected = runCatching { session.detach() }.isFailure
                probe.root(emptyList())
            }
        holder.delegate = session.state(0)
        session.attach()
        assertTrue(observedAttached)
        assertTrue(declarationRejected)
        assertTrue(bindRejected)
        assertTrue(mutationRejected)
        assertTrue(scopeRejected)
        assertTrue(lifecycleRejected)
        assertEquals(UiSessionState.Attached, session.lifecycleState)
        session.close()
    }

    @Test
    fun primaryFailureAndAllCleanupFailuresAreRetainedInOrder() {
        val primary = IllegalStateException("primary")
        val firstClose = IllegalStateException("first close")
        val secondClose = IllegalStateException("second close")
        val sourceOne = FailingSource(firstClose)
        val sourceTwo = FailingSource(secondClose)
        lateinit var session: UiSession
        session =
            UiSession(TestOwnerDispatcher()) {
                throw primary
            }
        session.bind(sourceOne)
        session.bind(sourceTwo)

        val thrown = assertThrows(IllegalStateException::class.java) { session.attach() }
        assertSame(primary, thrown)
        assertEquals(listOf(firstClose, secondClose), thrown.suppressed.toList())
        assertEquals(1, sourceOne.closeCount)
        assertEquals(1, sourceTwo.closeCount)
        session.close()
        assertEquals(UiSessionState.Closed, session.lifecycleState)
    }

    @Test
    fun normalClosePreservesBindingAndTreeCleanupOrderAndIdentity() {
        val firstClose = IllegalStateException("first close")
        val secondClose = IllegalStateException("second close")
        val detachFailure = IllegalStateException("detach")
        val disposeFailure = IllegalStateException("dispose")
        val probe =
            TestProbe(
                failingDetachTag = TestProbe.ProbeId("root"),
                failingDisposeTag = TestProbe.ProbeId("root"),
                detachFailure = detachFailure,
                disposeFailure = disposeFailure,
            )
        val firstSource = FailingSource(firstClose)
        val secondSource = FailingSource(secondClose)
        val session = UiSession(TestOwnerDispatcher()) { probe.root(emptyList()) }
        session.bind(firstSource)
        session.bind(secondSource)
        session.attach()
        val thrown = assertThrows(IllegalStateException::class.java) { session.close() }
        assertSame(firstClose, thrown)
        assertEquals(listOf(secondClose, detachFailure, disposeFailure), thrown.suppressed.toList())
        assertEquals(UiSessionState.Closed, session.lifecycleState)
        assertEquals(1, firstSource.closeCount)
        assertEquals(1, secondSource.closeCount)
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Detach })
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
        session.close()
        assertEquals(1, firstSource.closeCount)
        assertEquals(1, secondSource.closeCount)
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Detach })
        assertEquals(1, probe.events.count { event -> event is TestProbe.Event.Dispose })
    }

    private class Holder<T> {
        lateinit var delegate: ReadWriteProperty<Any?, T>

        var value: T
            get() = delegate.getValue(this, Holder<T>::value)
            set(next) = delegate.setValue(this, Holder<T>::value, next)
    }

    private class FailingSource(
        private val closeFailure: Throwable,
    ) : StateSource<Int> {
        var closeCount: Int = 0

        override fun subscribe(observer: (StateSnapshot<Int>) -> Unit): StateSubscription<Int> =
            StateSubscription(StateSnapshot(StateRevision(0), 0)) {
                closeCount += 1
                throw closeFailure
            }
    }
}
