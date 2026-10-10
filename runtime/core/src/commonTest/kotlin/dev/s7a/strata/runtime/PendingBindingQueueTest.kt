package dev.s7a.strata.runtime

import dev.s7a.strata.runtime.platform.synchronized
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent revision/cutoff oracle for selected declared bindings on JVM and JavaScript.
 */
internal class PendingBindingQueueTest {
    @Test
    fun idleSingleAndCompleteChangesSelectExactlyTheirCurrentTargets() {
        for (count in listOf(1, 128, 1_024)) {
            val queue = PendingBindingQueue<UiSessionBinding<Int>> { it.captureOrder }
            val bindings = List(count) { index -> binding(queue, index.toLong(), 0) }
            queue.capture(UiSessionBinding<*>::capturePending)
            assertEquals(emptyList(), queue.takeCaptured())
            val changed = bindings.last()
            changed.enqueue(StateSnapshot(StateRevision(1), 1))
            changed.enqueue(StateSnapshot(StateRevision(2), 2))
            changed.enqueue(StateSnapshot(StateRevision(1), 99))
            queue.capture(UiSessionBinding<*>::capturePending)
            val selected = queue.takeCaptured()
            assertEquals(listOf(changed), selected)
            assertTrue(selected.single().applyPending())
            assertEquals(2, changed.committedValue)
            queue.capture(UiSessionBinding<*>::capturePending)
            assertEquals(emptyList(), queue.takeCaptured())
            bindings.asReversed().forEach { it.enqueue(StateSnapshot(StateRevision(3), 3)) }
            queue.capture(UiSessionBinding<*>::capturePending)
            assertEquals(bindings, queue.takeCaptured().also { current -> current.forEach { assertTrue(it.applyPending()) } })
            bindings.forEach(UiSessionBinding<*>::disable)
            queue.clear()
        }
    }

    @Test
    fun newerEqualValueCommitsRevisionAndDoesNotReplayOrNotify() {
        val queue = PendingBindingQueue<UiSessionBinding<Int>> { it.captureOrder }
        val binding = binding(queue, 0, 7)
        binding.enqueue(StateSnapshot(StateRevision(2), 7))
        queue.capture(UiSessionBinding<*>::capturePending)
        assertFalse(queue.takeCaptured().single().applyPending())
        binding.enqueue(StateSnapshot(StateRevision(1), 99))
        binding.enqueue(StateSnapshot(StateRevision(2), 99))
        queue.capture(UiSessionBinding<*>::capturePending)
        assertEquals(emptyList(), queue.takeCaptured())
        assertEquals(7, binding.committedValue)
        binding.disable()
    }

    @Test
    fun comparisonsRunAfterEverySelectedSnapshotFreezesAndLateRevisionsWait() {
        val queue = PendingBindingQueue<UiSessionBinding<Value>> { it.captureOrder }
        val second = binding(queue, 1, Value(0))
        val first = binding(queue, 0, Value(0) { second.enqueue(StateSnapshot(StateRevision(2), Value(2))) })
        first.enqueue(StateSnapshot(StateRevision(1), Value(1)))
        second.enqueue(StateSnapshot(StateRevision(1), Value(1)))
        queue.capture(UiSessionBinding<*>::capturePending)
        val selected = queue.takeCaptured()
        assertEquals(listOf(first, second), selected)
        selected.forEach { assertTrue(it.applyPending()) }
        assertEquals(1, second.committedValue.number)
        queue.capture(UiSessionBinding<*>::capturePending)
        assertEquals(listOf(second), queue.takeCaptured().also { assertTrue(it.single().applyPending()) })
        assertEquals(2, second.committedValue.number)
        first.disable()
        second.disable()
    }

    @Test
    fun callbackBeforeInitialSnapshotIsCoalescedAndFailedCutoffCleanupReleasesEveryTarget() {
        val queue = PendingBindingQueue<UiSessionBinding<Value>> { it.captureOrder }
        val failure = IllegalStateException("Comparison failed")
        val first = binding(queue, 0, Value(0) { throw failure })
        val second = binding(queue, 1, Value(0))
        second.enqueue(StateSnapshot(StateRevision(1), Value(1)))
        second.commitInitial(StateSnapshot(StateRevision(2), Value(2)))
        queue.capture(UiSessionBinding<*>::capturePending)
        assertEquals(emptyList(), queue.takeCaptured())
        first.enqueue(StateSnapshot(StateRevision(3), Value(3)))
        second.enqueue(StateSnapshot(StateRevision(3), Value(3)))
        queue.capture(UiSessionBinding<*>::capturePending)
        assertSame(failure, assertFailsWith<IllegalStateException> { queue.takeCaptured().first().applyPending() })
        first.disable()
        second.disable()
        queue.clear()
        first.enqueue(StateSnapshot(StateRevision(4), Value(4)))
        second.enqueue(StateSnapshot(StateRevision(4), Value(4)))
        queue.capture(UiSessionBinding<*>::capturePending)
        assertEquals(emptyList(), queue.takeCaptured())
    }

    @Test
    fun callbackPublishedAfterCompleteFreezeStaysOutsideTheCurrentSelection() {
        val queue = PendingBindingQueue<UiSessionBinding<Int>> { it.captureOrder }
        val first = binding(queue, 0, 0)
        val second = binding(queue, 1, 0)
        first.enqueue(StateSnapshot(StateRevision(1), 1))
        queue.capture(UiSessionBinding<*>::capturePending)
        second.enqueue(StateSnapshot(StateRevision(1), 1))
        val selected = queue.takeCaptured()
        assertEquals(listOf(first), selected)
        assertTrue(selected.single().applyPending())
        assertEquals(0, second.committedValue)
        queue.capture(UiSessionBinding<*>::capturePending)
        assertEquals(listOf(second), queue.takeCaptured())
        assertTrue(second.applyPending())
        first.disable()
        second.disable()
    }

    private fun <T> binding(
        queue: PendingBindingQueue<UiSessionBinding<T>>,
        order: Long,
        initial: T,
    ): UiSessionBinding<T> {
        val binding = UiSessionBinding<T>({}, {}, {}, queue.monitor, queue::enqueue, queue::remove)
        binding.captureOrder = order
        synchronized(queue.monitor) { binding.commitInitial(StateSnapshot(StateRevision(0), initial)) }
        return binding
    }

    /**
     * Caller equality that can publish a following revision or fail without entering runtime work.
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
