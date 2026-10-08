package dev.s7a.strata.runtime

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies shared-lock capture, off-thread publication during equality and bounded terminal callback ownership.
 */
internal class PendingBindingConcurrencyTest {
    @Test
    fun publisherDuringEqualityCompletesWithoutTheCutoffLockAndTargetsTheFollowingFrame() {
        val queue = PendingBindingQueue<UiSessionBinding<Value>> { it.captureOrder }
        val executor = Executors.newSingleThreadExecutor()
        val second = binding(queue, 1, Value(0))
        val first = binding(queue, 0, Value(0) {
            executor.submit { second.enqueue(StateSnapshot(StateRevision(2), Value(2))) }.get(5, TimeUnit.SECONDS)
        })
        try {
            first.enqueue(StateSnapshot(StateRevision(1), Value(1)))
            second.enqueue(StateSnapshot(StateRevision(1), Value(1)))
            queue.capture { current ->
                assertTrue(Thread.holdsLock(queue.monitor))
                current.capturePending()
            }
            queue.takeCaptured().forEach { assertTrue(it.applyPending()) }
            assertEquals(1, second.committedValue.number)
            queue.capture(UiSessionBinding<*>::capturePending)
            assertEquals(listOf(second), queue.takeCaptured().also { assertTrue(it.single().applyPending()) })
            assertEquals(2, second.committedValue.number)
        } finally {
            first.disable()
            second.disable()
            queue.clear()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun concurrentPublishersCoalesceWithoutDroppingTheirLatestRevision() {
        val queue = PendingBindingQueue<UiSessionBinding<Int>> { it.captureOrder }
        val bindings = List(128) { index -> binding(queue, index.toLong(), 0) }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val publishing = bindings.map { current ->
                executor.submit {
                    repeat(100) { index -> current.enqueue(StateSnapshot(StateRevision(index.toLong() + 1), index + 1)) }
                }
            }
            repeat(10) {
                queue.capture(UiSessionBinding<*>::capturePending)
                queue.takeCaptured().forEach { it.applyPending() }
            }
            publishing.forEach { it.get(5, TimeUnit.SECONDS) }
            queue.capture(UiSessionBinding<*>::capturePending)
            queue.takeCaptured().forEach { it.applyPending() }
            assertEquals(List(128) { 100 }, bindings.map { it.committedValue })
            queue.capture(UiSessionBinding<*>::capturePending)
            assertEquals(emptyList(), queue.takeCaptured())
        } finally {
            bindings.forEach(UiSessionBinding<*>::disable)
            queue.clear()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun sourceChurnReleasesQueuedTargetsConstructionCallbacksAndPastPendingCapacity() {
        val queue = PendingBindingQueue<UiSessionBinding<Int>> { it.captureOrder }
        val bindings = List(1_024) { index -> binding(queue, index.toLong(), 0) }
        bindings.forEach { it.enqueue(StateSnapshot(StateRevision(1), 1)) }
        val pendingField = queue.javaClass.getDeclaredField("pending").also { it.isAccessible = true }
        val peakField = queue.javaClass.getDeclaredField("pendingPeak").also { it.isAccessible = true }
        val capturedField = queue.javaClass.getDeclaredField("captured").also { it.isAccessible = true }
        bindings.forEach { current ->
            current.disable()
            for (name in listOf("onPending", "onIdle", "pending", "captured")) {
                val field = current.javaClass.getDeclaredField(name)
                field.isAccessible = true
                assertNull(field.get(current), name)
            }
            val pending = pendingField.get(queue) as Set<*>
            val peak = peakField.getInt(queue)
            assertTrue(peak.toLong() <= pending.size.toLong() * 2)
        }
        queue.clear()
        assertEquals(emptySet(), pendingField.get(queue))
        assertEquals(emptyList(), capturedField.get(queue))
        assertEquals(0, peakField.getInt(queue))
        bindings.forEach { it.enqueue(StateSnapshot(StateRevision(2), 2)) }
        queue.capture(UiSessionBinding<*>::capturePending)
        assertEquals(emptyList(), queue.takeCaptured())
    }

    private fun <T> binding(
        queue: PendingBindingQueue<UiSessionBinding<T>>,
        order: Long,
        initial: T,
    ): UiSessionBinding<T> {
        val binding = UiSessionBinding<T>({}, {}, {}, queue.monitor, queue::enqueue, queue::remove)
        binding.captureOrder = order
        binding.commitInitial(StateSnapshot(StateRevision(0), initial))
        return binding
    }

    /**
     * Equality callback executed after the owner has completely released the shared cutoff lock.
     */
    private class Value(
        val number: Int,
        private val compared: () -> Unit = {},
    ) {
        override fun equals(other: Any?): Boolean {
            compared()
            return other is Value && number == other.number
        }

        override fun hashCode(): Int = number
    }
}
