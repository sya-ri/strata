@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.internal.platform.EvaluationContext
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies physical-thread isolation, serial owner migration and terminal operation reference release.
 */
internal class StateObservationOperationIsolationTest {
    @Test
    fun overlappingThreadsKeepTheirOwnOperationGuards() {
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val rejecting = executor.submit<Int> { guardedWrite(true, ready, release) }
            val accepting = executor.submit<Int> { guardedWrite(false, ready, release) }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(0, rejecting.get(5, TimeUnit.SECONDS))
            assertEquals(1, accepting.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun serialOwnerMigrationStartsWithAnEmptyOperationContext() {
        val owner = RuntimeExecutionOwner()
        var validations = 0
        val observation = owner.run { StateObservation({}, {}, {}, { validations += 1 }) }
        val state = owner.run { mutableStateOf(0) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            repeat(10) { index ->
                val execution =
                    executor.submit {
                        owner.run {
                            assertReleased(observation)
                            observation.enterOperation()
                            try {
                                state.value = index * 2 + 1
                            } finally {
                                observation.leaveOperation()
                            }
                            assertReleased(observation)
                        }
                    }
                execution.get(5, TimeUnit.SECONDS)
                owner.run {
                    observation.enterOperation()
                    try {
                        state.value = index * 2 + 2
                    } finally {
                        observation.leaveOperation()
                    }
                    assertReleased(observation)
                }
            }
            assertEquals(20, validations)
        } finally {
            owner.run { observation.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun completedAndClosedOperationsRetainNoObservationLinksOrThreadContext() {
        val observations = List(128) { StateObservation({}, {}, {}, {}) }
        try {
            repeat(10) {
                observations.forEach(StateObservation::enterOperation)
                observations.asReversed().forEach(StateObservation::leaveOperation)
                observations.forEach(::assertReleased)
            }
            observations.forEach(StateObservation::enterOperation)
            observations.forEach(StateObservation::close)
            observations.asReversed().forEach(StateObservation::leaveOperation)
            observations.forEach(::assertReleased)
        } finally {
            observations.forEach(StateObservation::close)
        }
    }

    private fun guardedWrite(
        reject: Boolean,
        ready: CountDownLatch,
        release: CountDownLatch,
    ): Int {
        val failure = IllegalStateException("This owner rejects mutation")
        val state = mutableStateOf(0)
        var validations = 0
        val observation =
            StateObservation({}, {}, {}, {
                validations += 1
                if (reject) throw failure
            })
        observation.enterOperation()
        try {
            ready.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            if (reject) {
                assertSame(failure, assertFailsWith<IllegalStateException> { state.value = 1 })
            } else {
                state.value = 1
            }
            assertEquals(1, validations)
            return state.value
        } finally {
            observation.close()
            observation.leaveOperation()
            assertReleased(observation)
            state.value = 2
            assertEquals(1, validations)
        }
    }

    private fun assertReleased(observation: StateObservation) {
        // Structural null checks are deterministic and do not depend on JVM garbage collection timing.
        for (name in listOf("previousOperation", "nextOperation", "lastOperation")) {
            val field = StateObservation::class.java.getDeclaredField(name)
            field.isAccessible = true
            assertNull(field.get(observation), name)
        }
        val field = StateObservation::class.java.getDeclaredField("operations")
        field.isAccessible = true
        val context = field.get(null) as EvaluationContext<*>
        assertNull(context.current)
    }
}
