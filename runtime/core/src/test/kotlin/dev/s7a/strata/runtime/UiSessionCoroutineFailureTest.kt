package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.properties.ReadWriteProperty

/**
 * Verifies supervisor isolation and owner-thread task failure handling.
 */
internal class UiSessionCoroutineFailureTest {
    @Test
    fun normalHandlerKeepsSiblingsAndSessionAlive() {
        val dispatcher = TestOwnerDispatcher()
        val failures = ArrayList<Throwable>()
        var siblingRan = false
        val session =
            UiSession(dispatcher, { failure ->
                failures.add(failure)
                UiTaskFailureDecision.Continue
            }) {
                TestProbe().root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw IllegalArgumentException("task") }
        session.screenScope.launch { siblingRan = true }
        dispatcher.drain()
        assertEquals(1, failures.size)
        assertTrue(siblingRan)
        assertEquals(UiSessionState.Attached, session.lifecycleState)
        session.close()
    }

    @Test
    fun handledFailureLeavesSuspendedSiblingActiveUntilReleased() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("handled task")
        val siblingStarted = CountDownLatch(1)
        val releaseSibling = CompletableDeferred<Unit>()
        var siblingCompleted = false
        val session =
            UiSession(dispatcher, { UiTaskFailureDecision.Continue }) {
                TestProbe().root(emptyList())
            }
        session.attach()
        val sibling =
            session.screenScope.launch {
                siblingStarted.countDown()
                releaseSibling.await()
                siblingCompleted = true
            }
        session.screenScope.launch { throw failure }
        dispatcher.drain()
        assertTrue(siblingStarted.await(5, TimeUnit.SECONDS))
        assertTrue(sibling.isActive)
        assertTrue(siblingCompleted.not())
        releaseSibling.complete(Unit)
        dispatcher.drain()
        assertTrue(siblingCompleted)
        session.close()
    }

    @Test
    fun cancellationDoesNotReachHandler() {
        val dispatcher = TestOwnerDispatcher()
        val failures = ArrayList<Throwable>()
        val session =
            UiSession(dispatcher, { failure ->
                failures.add(failure)
                UiTaskFailureDecision.Continue
            }) {
                TestProbe().root(emptyList())
            }
        session.attach()
        val job = session.screenScope.launch { throw CancellationException("cancel") }
        dispatcher.drain()
        assertTrue(job.isCancelled)
        assertTrue(failures.isEmpty())
        session.close()
    }

    @Test
    fun defaultHandlerPoisonsWithOriginalFailure() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalArgumentException("task")
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()
        session.screenScope.launch { throw failure }
        val thrown = runCatching { dispatcher.drain() }.exceptionOrNull()
        assertSame(failure, thrown)
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        session.close()
    }

    @Test
    fun sameInstanceHandlerFailureDoesNotSelfSuppress() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("same failure")
        val session =
            UiSession(dispatcher, { throw failure }) {
                TestProbe().root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.drain() }
        assertSame(failure, thrown)
        assertTrue(thrown.suppressed.isEmpty())
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        session.close()
    }

    @Test
    fun distinctHandlerFailureIsSuppressedOnTaskPrimary() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("task primary")
        val handlerFailure = IllegalArgumentException("handler failure")
        val session =
            UiSession(dispatcher, { throw handlerFailure }) {
                TestProbe().root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.drain() }
        assertSame(failure, thrown)
        assertEquals(listOf(handlerFailure), thrown.suppressed.toList())
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        session.close()
    }

    @Test
    fun handlerReentrantCloseBecomesSuppressedFailure() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("task primary")
        var capturedHandlerFailure: Throwable? = null
        lateinit var session: UiSession
        session =
            UiSession(dispatcher, {
                try {
                    session.close()
                } catch (caught: Throwable) {
                    capturedHandlerFailure = caught
                    throw caught
                }
                UiTaskFailureDecision.Continue
            }) {
                TestProbe().root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.drain() }
        val handlerFailure = checkNotNull(capturedHandlerFailure)
        assertSame(failure, thrown)
        assertEquals(listOf(handlerFailure), thrown.suppressed.toList())
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        session.close()
    }

    @Test
    fun handlerStateWriteMarksExactlyOneSubsequentRebuild() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("dirty task")
        val holder = StateHolder()
        val probe = TestProbe()
        var contentCalls = 0
        val session =
            UiSession(dispatcher, {
                holder.value = 1
                UiTaskFailureDecision.Continue
            }) {
                contentCalls += 1
                holder.value
                probe.root(emptyList())
            }
        holder.delegate = session.state(0)
        session.attach()
        session.screenScope.launch { throw failure }
        dispatcher.drain()
        assertEquals(1, contentCalls)
        session.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentCalls)
        session.frame(Constraints.fixed(2, 1))
        assertEquals(2, contentCalls)
        session.close()
    }

    @Test
    fun taskAndCleanupFailuresPreserveExactAsyncOrderAndSingleCleanup() {
        val dispatcher = TestOwnerDispatcher()
        val taskFailure = IllegalStateException("task primary")
        val handlerFailure = IllegalArgumentException("handler failure")
        val firstBindingFailure = IllegalStateException("first binding")
        val secondBindingFailure = IllegalStateException("second binding")
        val detachFailure = IllegalStateException("tree detach")
        val disposeFailure = IllegalStateException("tree dispose")
        val rootTag = TestProbe.ProbeId("root")
        val probe =
            TestProbe(
                failingDetachTag = rootTag,
                failingDisposeTag = rootTag,
                detachFailure = detachFailure,
                disposeFailure = disposeFailure,
            )
        val firstSource = FailingSource(StateSnapshot(StateRevision(0), 1), firstBindingFailure)
        val secondSource = FailingSource(StateSnapshot(StateRevision(0), 2), secondBindingFailure)
        val session =
            UiSession(dispatcher, { throw handlerFailure }) {
                probe.root(emptyList())
            }
        session.bind(firstSource)
        session.bind(secondSource)
        session.attach()
        session.screenScope.launch { throw taskFailure }
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.drain() }
        assertSame(taskFailure, thrown)
        assertEquals(
            listOf(handlerFailure, firstBindingFailure, secondBindingFailure, detachFailure, disposeFailure),
            thrown.suppressed.toList(),
        )
        assertEquals(1, firstSource.closeCount)
        assertEquals(1, secondSource.closeCount)
        assertEquals(
            listOf(
                TestProbe.Event.Attach(rootTag),
                TestProbe.Event.Detach(rootTag),
                TestProbe.Event.Dispose(rootTag),
            ),
            probe.events,
        )
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        session.close()
        session.close()
        assertEquals(UiSessionState.Closed, session.lifecycleState)
        assertEquals(1, firstSource.closeCount)
        assertEquals(1, secondSource.closeCount)
        assertEquals(3, probe.events.size)
    }

    private class StateHolder {
        lateinit var delegate: ReadWriteProperty<Any?, Int>

        var value: Int
            get() = delegate.getValue(this, ::value)
            set(next) = delegate.setValue(this, ::value, next)
    }

    private class FailingSource<T>(
        private val initial: StateSnapshot<T>,
        private val closeFailure: Throwable,
    ) : StateSource<T> {
        var closeCount: Int = 0

        override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> =
            StateSubscription(initial) {
                closeCount += 1
                throw closeFailure
            }
    }
}
