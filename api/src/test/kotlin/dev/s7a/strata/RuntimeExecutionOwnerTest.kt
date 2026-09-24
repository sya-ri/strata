@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.spi.ExecutionOwnerId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.mutableStateOf
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises real thread migration, mutual exclusion, context restoration, and ordinary thread confinement.
 */
internal class RuntimeExecutionOwnerTest {
    @Test
    fun stateFollowsItsSerialOwnerAcrossThreadsWithoutCrossingOtherOwners() {
        val owner = RuntimeExecutionOwner()
        val ownerId = owner.run { RuntimeExecutionOwner.current() }
        val state = owner.run { mutableStateOf(1) }
        val text = owner.run { TextFieldState("before") }
        val physical = mutableStateOf(9)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    val physicalOwner = RuntimeExecutionOwner.current()
                    assertFailsWith<IllegalStateException> { state.value }
                    assertFailsWith<IllegalStateException> { physical.value }
                    owner.run {
                        assertEquals(ownerId, RuntimeExecutionOwner.current())
                        state.value += 1
                        text.value = "after"
                        assertEquals("after", text.value)
                        owner.run { assertEquals(2, state.value) }
                        RuntimeExecutionOwner().run {
                            assertFailsWith<IllegalStateException> { state.value }
                        }
                        assertEquals(2, state.value)
                    }
                    assertEquals(physicalOwner, RuntimeExecutionOwner.current())
                }.get(5, TimeUnit.SECONDS)
            owner.run {
                assertEquals(2, state.value)
                assertEquals("after", text.value)
            }
            assertEquals(9, physical.value)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun concurrentEntryFailsAndThrowingWorkReleasesTheOwner() {
        val owner = RuntimeExecutionOwner()
        val physicalOwner = RuntimeExecutionOwner.current()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val task =
                executor.submit {
                    owner.run {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { owner.run { error("Must not execute") } }
            release.countDown()
            task.get(5, TimeUnit.SECONDS)
            assertFailsWith<IllegalArgumentException> { owner.run { throw IllegalArgumentException("failure") } }
            assertEquals(physicalOwner, RuntimeExecutionOwner.current())
            assertEquals(7, owner.run { 7 })
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun physicalOwnersRemainDistinctWhenThreadsHaveEqualValues() {
        val identities = ConcurrentLinkedQueue<ExecutionOwnerId>()
        val threads = List(2) { ValueEqualThread { identities.add(RuntimeExecutionOwner.current()) } }
        assertEquals(threads[0], threads[1])
        threads.forEach(Thread::start)
        threads.forEach { thread ->
            thread.join(5000)
            assertTrue(thread.isAlive.not())
        }
        assertEquals(2, identities.size)
        assertEquals(2, identities.toSet().size)
    }

    /**
     * A host thread whose value equality must never merge independent execution owners.
     */
    private class ValueEqualThread(
        operation: () -> Unit,
    ) : Thread(operation) {
        override fun equals(other: Any?): Boolean = other is ValueEqualThread

        override fun hashCode(): Int = 0
    }
}
