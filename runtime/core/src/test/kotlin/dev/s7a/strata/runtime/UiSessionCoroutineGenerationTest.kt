package dev.s7a.strata.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.properties.ReadWriteProperty

/**
 * Verifies stable screen scope identity and owner-dispatched generation behavior.
 */
internal class UiSessionCoroutineGenerationTest {
    @Test
    fun inactiveLaunchesNeverRunAndJobsTrackEveryGeneration() {
        val dispatcher = TestOwnerDispatcher()
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        val scope = session.screenScope
        var inactiveRuns = 0
        var activeRuns = 0

        val createdJob = scope.coroutineContext[Job]
        assertTrue(createdJob?.isActive == false)
        assertSame(createdJob, readJobOnWorker(scope))
        scope.launch { inactiveRuns += 1 }

        session.attach()
        val firstJob = scope.coroutineContext[Job]
        assertSame(scope, session.screenScope)
        assertTrue(firstJob?.isActive == true)
        assertSame(firstJob, readJobOnWorker(scope))
        scope.launch { activeRuns += 1 }
        dispatcher.drain()
        assertEquals(1, activeRuns)

        session.detach()
        assertTrue(firstJob?.isCancelled == true)
        val detachedJob = scope.coroutineContext[Job]
        assertTrue(detachedJob?.isCancelled == true)
        assertTrue(detachedJob !== firstJob)
        assertSame(detachedJob, readJobOnWorker(scope))
        scope.launch { inactiveRuns += 1 }

        session.attach()
        val secondJob = scope.coroutineContext[Job]
        assertTrue(secondJob?.isActive == true)
        assertTrue(secondJob !== firstJob)
        assertSame(secondJob, readJobOnWorker(scope))
        scope.launch { activeRuns += 1 }
        dispatcher.drain()
        assertEquals(2, activeRuns)

        session.close()
        val closedJob = scope.coroutineContext[Job]
        assertTrue(closedJob?.isActive == false)
        assertTrue(closedJob !== secondJob)
        assertSame(closedJob, readJobOnWorker(scope))
        scope.launch { inactiveRuns += 1 }
        dispatcher.drain()
        assertEquals(0, inactiveRuns)
    }

    @Test
    fun failedAndClosedLaunchesNeverRun() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("generation failure")
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        val scope = session.screenScope
        var bodyRuns = 0
        session.attach()
        scope.launch { throw failure }
        runCatching { dispatcher.drain() }
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        val failedJob = scope.coroutineContext[Job]
        assertTrue(failedJob?.isCancelled == true)
        assertSame(failedJob, readJobOnWorker(scope))
        scope.launch { bodyRuns += 1 }
        dispatcher.drain()
        assertEquals(0, bodyRuns)
        session.close()
        scope.launch { bodyRuns += 1 }
        dispatcher.drain()
        assertEquals(0, bodyRuns)
    }

    @Test
    fun launchWaitsForOwnerDispatcherAndRunsOnOwner() {
        val dispatcher = TestOwnerDispatcher()
        val owner = Thread.currentThread()
        var bodyThread: Thread? = null
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()
        session.screenScope.launch { bodyThread = Thread.currentThread() }
        assertEquals(null, bodyThread)
        dispatcher.drain()
        assertSame(owner, bodyThread)
        session.close()
    }

    @Test
    fun anActiveGenerationMayLaunchAQueuedChildFromItsOwnerContinuation() {
        val dispatcher = TestOwnerDispatcher()
        var childRuns = 0
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()

        session.screenScope.launch {
            session.screenScope.launch { childRuns += 1 }
        }
        dispatcher.drain()

        assertEquals(1, childRuns)
        session.close()
    }

    @Test
    fun inlineOwnerDispatcherIsRejectedBeforeBody() {
        val bodyRan = booleanArrayOf(false)
        val session = UiSession(InlineDispatcher()) { TestProbe().root(emptyList()) }
        session.attach()
        assertThrows(IllegalStateException::class.java) {
            session.screenScope.launch { bodyRan[0] = true }
        }
        assertTrue(bodyRan[0].not())
        session.close()
    }

    @Test
    fun queuedWrongThreadOwnerDispatcherIsRejectedBeforeBody() {
        val dispatcher = TestOwnerDispatcher()
        val bodyRan = booleanArrayOf(false)
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()
        session.screenScope.launch { bodyRan[0] = true }
        val task =
            FutureTask<Throwable?> {
                runCatching { dispatcher.drain() }.exceptionOrNull()
            }
        val thread = Thread(task)
        thread.start()
        val thrown = task.get(5, TimeUnit.SECONDS)
        assertTrue(thrown is IllegalStateException)
        assertTrue(bodyRan[0].not())
        session.close()
    }

    @Test
    fun ownerQueueMayRunBeforeWorkerDispatchReturns() {
        val owner = Thread.currentThread()
        val dispatcher = FastOwnerQueueDispatcher(owner)
        val bodyThread = arrayOfNulls<Thread>(1)
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()
        val task =
            FutureTask<Unit> {
                session.screenScope.launch { bodyThread[0] = Thread.currentThread() }
            }
        val thread = Thread(task)
        thread.start()
        assertTrue(dispatcher.dispatchStarted.await(5, TimeUnit.SECONDS))
        dispatcher.runOneOnOwner()
        task.get(5, TimeUnit.SECONDS)
        assertSame(owner, bodyThread[0])
        session.close()
    }

    @Test
    fun detachedCancellationFinallyRunsWhenDispatcherIsDriven() {
        val dispatcher = TestOwnerDispatcher()
        var finallyThread: Thread? = null
        var finallyCount = 0
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()
        session.screenScope.launch {
            try {
                awaitCancellation()
            } finally {
                finallyCount += 1
                finallyThread = Thread.currentThread()
            }
        }
        assertTrue(dispatcher.runNext(5_000))
        session.detach()
        assertEquals(null, finallyThread)
        dispatcher.drain()
        assertSame(Thread.currentThread(), finallyThread)
        assertEquals(1, finallyCount)
        dispatcher.drain()
        assertEquals(1, finallyCount)
        session.close()
    }

    @Test
    fun closedCancellationFinallyRunsExactlyOnceWhenDispatcherIsDriven() {
        val dispatcher = TestOwnerDispatcher()
        var finallyThread: Thread? = null
        var finallyCount = 0
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()
        session.screenScope.launch {
            try {
                awaitCancellation()
            } finally {
                finallyCount += 1
                finallyThread = Thread.currentThread()
            }
        }
        assertTrue(dispatcher.runNext(5_000))
        session.close()
        assertEquals(null, finallyThread)
        dispatcher.drain()
        assertSame(Thread.currentThread(), finallyThread)
        assertEquals(1, finallyCount)
        dispatcher.drain()
        assertEquals(1, finallyCount)
    }

    @Test
    fun workerContextReturnsToOwnerAndDetachCancelsBeforeStateContinuation() {
        val dispatcher = TestOwnerDispatcher()
        val workerExecutor = Executors.newSingleThreadExecutor()
        val worker = workerExecutor.asCoroutineDispatcher()
        val workerStarted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val workerFinished = CountDownLatch(1)
        var resumed = false
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        session.attach()
        session.screenScope.launch {
            withContext(worker) {
                workerStarted.countDown()
                releaseWorker.await()
                workerFinished.countDown()
            }
            resumed = true
        }
        assertTrue(dispatcher.runNext(5_000))
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS))
        session.detach()
        releaseWorker.countDown()
        assertTrue(workerFinished.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.runNext(5_000))
        assertTrue(resumed.not())
        worker.close()
        workerExecutor.shutdown()
        workerExecutor.awaitTermination(5, TimeUnit.SECONDS)
        session.close()
    }

    @Test
    fun workerStateAccessReportsOnOwnerAndWithContextResumesOnOwner() {
        val dispatcher = TestOwnerDispatcher()
        val workerExecutor = Executors.newSingleThreadExecutor()
        val worker = workerExecutor.asCoroutineDispatcher()
        val workerStarted = CountDownLatch(1)
        val failureOnOwner = arrayOfNulls<Thread>(1)
        val failures = ArrayList<Throwable>()
        val holder = StateHolder()
        lateinit var session: UiSession
        session =
            UiSession(dispatcher, { failure ->
                failures.add(failure)
                failureOnOwner[0] = Thread.currentThread()
                UiTaskFailureDecision.Continue
            }) {
                TestProbe().root(emptyList())
            }
        holder.delegate = session.state(0)
        session.attach()
        session.screenScope.launch(worker) {
            workerStarted.countDown()
            holder.value
        }
        assertTrue(workerStarted.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.runNext(5_000))
        assertEquals(1, failures.size)
        assertSame(Thread.currentThread(), failureOnOwner[0])

        val workerResumed = CountDownLatch(1)
        var resumedThread: Thread? = null
        session.screenScope.launch {
            withContext(worker) {
                workerResumed.countDown()
            }
            resumedThread = Thread.currentThread()
        }
        assertTrue(dispatcher.runNext(5_000))
        assertTrue(workerResumed.await(5, TimeUnit.SECONDS))
        assertEquals(null, resumedThread)
        assertTrue(dispatcher.runNext(5_000))
        assertSame(Thread.currentThread(), resumedThread)
        worker.close()
        workerExecutor.shutdown()
        workerExecutor.awaitTermination(5, TimeUnit.SECONDS)
        session.close()
    }

    @Test
    fun staleFinallyCannotReadWriteOrLaunchIntoAReattachedGeneration() {
        val dispatcher = TestOwnerDispatcher()
        val holder = StateHolder()
        val session = UiSession(dispatcher) { TestProbe().root(emptyList()) }
        holder.delegate = session.state(0)
        session.attach()
        var readRejected = false
        var writeRejected = false
        var launchRejected = false
        session.screenScope.launch {
            try {
                awaitCancellation()
            } finally {
                readRejected = runCatching { holder.value }.isFailure
                writeRejected = runCatching { holder.value = 1 }.isFailure
                launchRejected = runCatching { session.screenScope.launch { holder.value = 2 } }.isFailure
            }
        }
        dispatcher.drain()
        session.detach()
        session.attach()
        dispatcher.drain()
        assertTrue(readRejected)
        assertTrue(writeRejected)
        assertTrue(launchRejected)
        assertEquals(0, holder.value)
        session.close()
    }

    @Test
    fun contentScopeAccessPoisonsTheSession() {
        lateinit var session: UiSession
        session =
            UiSession(TestOwnerDispatcher()) {
                session.screenScope.launch { }
                TestProbe().root(emptyList())
            }
        assertThrows(IllegalStateException::class.java) { session.attach() }
        assertTrue(session.lifecycleState is UiSessionState.Failed)
        session.close()
    }

    @Test
    fun staleContinueCannotTouchAReattachedGeneration() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("stale continue")
        val holder = StateHolder()
        val failures = ArrayList<Throwable>()
        var readRejected = false
        var writeRejected = false
        var launchRejected = false
        lateinit var session: UiSession
        session =
            UiSession(dispatcher, { taskFailure ->
                failures.add(taskFailure)
                readRejected = runCatching { holder.value }.isFailure
                writeRejected = runCatching { holder.value = 1 }.isFailure
                launchRejected = runCatching { session.screenScope.launch { holder.value = 2 } }.isFailure
                UiTaskFailureDecision.Continue
            }) {
                TestProbe().root(emptyList())
            }
        holder.delegate = session.state(0)
        session.attach()
        session.screenScope.launch { throw failure }
        assertTrue(dispatcher.runNext(5_000))
        session.detach()
        session.attach()
        assertTrue(dispatcher.runNext(5_000))
        assertEquals(listOf(failure), failures)
        assertTrue(readRejected)
        assertTrue(writeRejected)
        assertTrue(launchRejected)
        assertEquals(0, holder.value)
        assertEquals(UiSessionState.Attached, session.lifecycleState)
        session.close()
    }

    @Test
    fun staleFailSessionThrowsOriginalAfterReattachWithoutCleanup() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("stale fail")
        val probe = TestProbe()
        val session =
            UiSession(dispatcher, { UiTaskFailureDecision.FailSession }) {
                probe.root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        assertTrue(dispatcher.runNext(5_000))
        session.detach()
        session.attach()
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.runNext(5_000) }
        assertSame(failure, thrown)
        assertEquals(UiSessionState.Attached, session.lifecycleState)
        assertEquals(listOf(TestProbe.Event.Attach(TestProbe.ProbeId("root"))), probe.events)
        session.close()
    }

    @Test
    fun staleContinueAfterCloseReturnsWithoutRepeatingCleanup() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("stale closed continue")
        val probe = TestProbe()
        val failures = ArrayList<Throwable>()
        var closeRejected = false
        lateinit var session: UiSession
        session =
            UiSession(dispatcher, { taskFailure ->
                failures.add(taskFailure)
                closeRejected = runCatching { session.close() }.isFailure
                UiTaskFailureDecision.Continue
            }) {
                probe.root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        assertTrue(dispatcher.runNext(5_000))
        session.close()
        val events = probe.events.toList()
        assertTrue(dispatcher.runNext(5_000))
        assertEquals(listOf(failure), failures)
        assertTrue(closeRejected)
        assertEquals(events, probe.events)
        assertEquals(UiSessionState.Closed, session.lifecycleState)
    }

    @Test
    fun staleFailSessionAfterCloseThrowsOriginalWithoutMutation() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("stale closed fail")
        val probe = TestProbe()
        val session =
            UiSession(dispatcher, { UiTaskFailureDecision.FailSession }) {
                probe.root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        assertTrue(dispatcher.runNext(5_000))
        session.close()
        val events = probe.events.toList()
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.runNext(5_000) }
        assertSame(failure, thrown)
        assertEquals(events, probe.events)
        assertEquals(UiSessionState.Closed, session.lifecycleState)
    }

    @Test
    fun staleThrowingHandlerKeepsTaskPrimaryAndSuppressesHandler() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("stale task")
        val handlerFailure = IllegalArgumentException("stale handler")
        val session =
            UiSession(dispatcher, { throw handlerFailure }) {
                TestProbe().root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        assertTrue(dispatcher.runNext(5_000))
        session.detach()
        session.attach()
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.runNext(5_000) }
        assertSame(failure, thrown)
        assertEquals(listOf(handlerFailure), thrown.suppressed.toList())
        assertEquals(UiSessionState.Attached, session.lifecycleState)
        session.close()
    }

    @Test
    fun staleThrowingHandlerAfterClosePreservesPrimaryAndClosedResources() {
        val dispatcher = TestOwnerDispatcher()
        val failure = IllegalStateException("closed stale task")
        val handlerFailure = IllegalArgumentException("closed stale handler")
        val probe = TestProbe()
        val session =
            UiSession(dispatcher, { throw handlerFailure }) {
                probe.root(emptyList())
            }
        session.attach()
        session.screenScope.launch { throw failure }
        assertTrue(dispatcher.runNext(5_000))
        session.close()
        val events = probe.events.toList()
        val thrown = assertThrows(IllegalStateException::class.java) { dispatcher.runNext(5_000) }
        assertSame(failure, thrown)
        assertEquals(listOf(handlerFailure), thrown.suppressed.toList())
        assertEquals(events, probe.events)
        assertEquals(UiSessionState.Closed, session.lifecycleState)
    }

    private fun readJobOnWorker(scope: CoroutineScope): Job? {
        val task = FutureTask<Job?> { scope.coroutineContext[Job] }
        val thread = Thread(task)
        thread.start()
        return task.get(5, TimeUnit.SECONDS)
    }

    private class StateHolder {
        lateinit var delegate: ReadWriteProperty<Any?, Int>

        var value: Int
            get() = delegate.getValue(this, ::value)
            set(next) = delegate.setValue(this, ::value, next)
    }

    private class InlineDispatcher : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            block.run()
        }
    }

    private class FastOwnerQueueDispatcher(
        private val owner: Thread,
    ) : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        private val workerMayReturn = CountDownLatch(1)
        val dispatchStarted = CountDownLatch(1)

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.add(block)
            dispatchStarted.countDown()
            if (Thread.currentThread() === owner) {
                return
            }
            check(workerMayReturn.await(5, TimeUnit.SECONDS)) { "Owner execution did not release the worker dispatch." }
        }

        fun runOneOnOwner() {
            check(Thread.currentThread() === owner)
            val block = queue.poll(5, TimeUnit.SECONDS)
            try {
                checkNotNull(block).run()
            } finally {
                workerMayReturn.countDown()
            }
        }
    }
}
