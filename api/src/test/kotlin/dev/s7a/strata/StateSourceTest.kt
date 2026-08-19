package dev.s7a.strata

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies source linearization, ordered delivery, and terminal observation behavior.
 */
internal class StateSourceTest {
    @Test
    fun registrationLinearizationLeavesNoConcurrentPublishGap() {
        val registered = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val received = CopyOnWriteArrayList<StateRevision>()
        val source =
            ConformingSource(
                initialValue = 0,
                beforeReturn = {
                    registered.countDown()
                    releaseRegistration.await()
                },
            )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val subscriptionFuture =
                executor.submit<StateSubscription<Int>> {
                    source.subscribe { snapshot -> received.add(snapshot.revision) }
                }
            assertTrue(registered.await(2, TimeUnit.SECONDS))
            val publishFuture = executor.submit { source.publish(1) }
            releaseRegistration.countDown()

            val subscription = subscriptionFuture.get(2, TimeUnit.SECONDS)
            publishFuture.get(2, TimeUnit.SECONDS)
            subscription.close()

            assertEquals(listOf(StateRevision(1)), received)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun callbackCanRunBeforeSubscribeReturns() {
        val returned = AtomicBoolean()
        val callbackBeforeReturn = AtomicBoolean()
        val source =
            ConformingSource(
                initialValue = 0,
                beforeReturnValue = { 1 },
            )
        val subscription =
            source.subscribe {
                callbackBeforeReturn.set(returned.get().not())
            }
        returned.set(true)
        subscription.close()

        assertTrue(callbackBeforeReturn.get())
        assertEquals(StateRevision(0), subscription.initialSnapshot.revision)
    }

    @Test
    fun concurrentPublishersDeliverStrictlyIncreasingRevisionsOnAWorkerThread() {
        val owner = Thread.currentThread()
        val revisions = CopyOnWriteArrayList<StateRevision>()
        val callbackThreads = CopyOnWriteArrayList<Thread>()
        val source = ConformingSource(0)
        val subscription =
            source.subscribe {
                revisions.add(it.revision)
                callbackThreads.add(Thread.currentThread())
            }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..32).map { value -> executor.submit { source.publish(value) } }
            futures.forEach { future -> future.get(2, TimeUnit.SECONDS) }

            assertEquals((1..32).map { value -> StateRevision(value.toLong()) }, revisions)
            assertTrue(callbackThreads.isNotEmpty())
            assertTrue(callbackThreads.any { thread -> thread !== owner })
        } finally {
            subscription.close()
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun equalRevisionsAcrossSubscriptionsIdentifyOneLogicalSnapshot() {
        val firstSnapshots = CopyOnWriteArrayList<StateSnapshot<Int>>()
        val secondSnapshots = CopyOnWriteArrayList<StateSnapshot<Int>>()
        val source = ConformingSource(0)
        val first = source.subscribe { snapshot -> firstSnapshots.add(snapshot) }
        val second = source.subscribe { snapshot -> secondSnapshots.add(snapshot) }

        assertEquals(first.initialSnapshot, second.initialSnapshot)
        source.publish(1)

        assertEquals(firstSnapshots.single(), secondSnapshots.single())
        assertEquals(firstSnapshots.single().revision, secondSnapshots.single().revision)
        first.close()
        second.close()
    }

    @Test
    fun callbacksNeverOverlapOrReenterAndReentrantPublishIsSerialized() {
        val activeCallbacks = AtomicInteger()
        val maximumActive = AtomicInteger()
        val publishDuringCallback = AtomicBoolean(true)
        val values = CopyOnWriteArrayList<Int>()
        val source = ConformingSource(0)
        val subscription =
            source.subscribe { snapshot ->
                val active = activeCallbacks.incrementAndGet()
                maximumActive.updateAndGet { current -> maxOf(current, active) }
                values.add(snapshot.value)
                if (publishDuringCallback.compareAndSet(true, false)) {
                    source.publish(2)
                }
                activeCallbacks.decrementAndGet()
            }

        source.publish(1)
        subscription.close()

        assertEquals(listOf(1, 2), values)
        assertEquals(1, maximumActive.get())
    }

    @Test
    fun closeFromCallbackStopsLaterNotifications() {
        val values = CopyOnWriteArrayList<Int>()
        val closeDuringCallback = AtomicBoolean(true)
        val source = ConformingSource(0)
        lateinit var subscription: StateSubscription<Int>
        subscription =
            source.subscribe { snapshot ->
                values.add(snapshot.value)
                if (closeDuringCallback.compareAndSet(true, false)) {
                    subscription.close()
                }
            }

        source.publish(1)
        source.publish(2)

        assertEquals(listOf(1), values)
    }

    @Test
    fun revisionExhaustionDoesNotMutateOrNotify() {
        val received = CopyOnWriteArrayList<StateSnapshot<Int>>()
        val source = ConformingSource(0, initialRevision = StateRevision(Long.MAX_VALUE))
        val subscription = source.subscribe { snapshot -> received.add(snapshot) }

        assertThrows(IllegalStateException::class.java) { source.publish(1) }
        val later = source.subscribe { }

        assertTrue(received.isEmpty())
        assertEquals(StateRevision(Long.MAX_VALUE), subscription.initialSnapshot.revision)
        assertEquals(StateRevision(Long.MAX_VALUE), later.initialSnapshot.revision)
        subscription.close()
        later.close()
    }

    private class ConformingSource<T>(
        initialValue: T,
        initialRevision: StateRevision = StateRevision(0),
        private val beforeReturn: (() -> Unit)? = null,
        private val beforeReturnValue: (() -> T)? = null,
    ) : StateSource<T> {
        private val lock = Any()
        private var current = StateSnapshot(initialRevision, initialValue)
        private var nextId = 0
        private val observers = LinkedHashMap<Int, Observer<T>>()
        private val pending = ArrayDeque<StateSnapshot<T>>()
        private var delivering = false

        override fun subscribe(observer: (StateSnapshot<T>) -> Unit): StateSubscription<T> {
            val registered: Observer<T>
            val initial: StateSnapshot<T>
            var shouldDrain = false
            synchronized(lock) {
                initial = current
                registered = Observer(nextId, initial.revision, observer)
                nextId += 1
                observers[registered.id] = registered
                beforeReturn?.invoke()
                beforeReturnValue?.let { value ->
                    shouldDrain = enqueueLocked(value()) || shouldDrain
                }
            }
            if (shouldDrain) {
                drain()
            }
            return StateSubscription(initial) {
                registered.close()
                synchronized(lock) {
                    observers.remove(registered.id)
                }
            }
        }

        fun publish(value: T) {
            val shouldDrain = synchronized(lock) { enqueueLocked(value) }
            if (shouldDrain) {
                drain()
            }
        }

        private fun enqueueLocked(value: T): Boolean {
            val currentValue = current.revision.value
            check(currentValue < Long.MAX_VALUE) { "State revision space is exhausted." }
            val next = StateSnapshot(StateRevision(currentValue + 1), value)
            current = next
            pending.addLast(next)
            val start = delivering.not()
            if (start) {
                delivering = true
            }
            return start
        }

        private fun drain() {
            try {
                while (true) {
                    val next: StateSnapshot<T>
                    val currentObservers: List<Observer<T>>
                    synchronized(lock) {
                        if (pending.isEmpty()) {
                            delivering = false
                            return
                        }
                        next = pending.removeFirst()
                        currentObservers = observers.values.toList()
                    }
                    currentObservers.forEach { observer -> observer.deliver(next) }
                }
            } catch (failure: Throwable) {
                synchronized(lock) {
                    delivering = false
                }
                throw failure
            }
        }

        private class Observer<T>(
            val id: Int,
            private val startRevision: StateRevision,
            private val observer: (StateSnapshot<T>) -> Unit,
        ) {
            private val lock = Any()
            private var closed = false

            fun deliver(snapshot: StateSnapshot<T>) {
                synchronized(lock) {
                    if (closed || snapshot.revision <= startRevision) {
                        return
                    }
                    observer(snapshot)
                }
            }

            fun close() {
                synchronized(lock) {
                    closed = true
                }
            }
        }
    }
}
