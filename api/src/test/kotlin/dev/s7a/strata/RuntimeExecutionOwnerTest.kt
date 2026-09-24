@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.mutableStateOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises real thread migration, mutual exclusion, context restoration, and ordinary thread confinement.
 */
internal class RuntimeExecutionOwnerTest {
    @Test
    fun stateFollowsItsSerialOwnerAcrossThreadsWithoutCrossingOtherOwners() {
        val owner = RuntimeExecutionOwner()
        val state = owner.run { mutableStateOf(1) }
        val text = owner.run { TextFieldState("before") }
        val physical = mutableStateOf(9)
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    assertFailsWith<IllegalStateException> { state.value }
                    assertFailsWith<IllegalStateException> { physical.value }
                    owner.run {
                        state.value += 1
                        text.value = "after"
                        assertEquals("after", text.value)
                        owner.run { assertEquals(2, state.value) }
                        RuntimeExecutionOwner().run {
                            assertFailsWith<IllegalStateException> { state.value }
                        }
                        assertEquals(2, state.value)
                    }
                    assertSame(Thread.currentThread(), RuntimeExecutionOwner.current())
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
            assertSame(Thread.currentThread(), RuntimeExecutionOwner.current())
            assertEquals(7, owner.run { 7 })
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
