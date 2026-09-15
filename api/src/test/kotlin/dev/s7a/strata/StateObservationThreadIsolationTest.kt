@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that overlapping owner-thread evaluations cannot change each other's dependency tracking or guards.
 */
internal class StateObservationThreadIsolationTest {
    @Test
    fun simultaneousEvaluationsTrackOnlyTheirOwnStates() {
        val firstEntered = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first =
                executor.submit<Int> {
                    try {
                        observeAcrossPause {
                            firstEntered.countDown()
                            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
                        }
                    } finally {
                        firstFinished.countDown()
                    }
                }
            val second =
                executor.submit<Int> {
                    assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                    observeAcrossPause {
                        secondEntered.countDown()
                        assertTrue(firstFinished.await(5, TimeUnit.SECONDS))
                    }
                }
            assertEquals(2, first.get(5, TimeUnit.SECONDS))
            assertEquals(2, second.get(5, TimeUnit.SECONDS))
        } finally {
            firstEntered.countDown()
            secondEntered.countDown()
            firstFinished.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun observeAcrossPause(pause: () -> Unit): Int {
        val before = mutableStateOf(0)
        val after = mutableStateOf(0)
        var invalidations = 0
        val observation = StateObservation({}, {}, { invalidations += 1 }, {})
        try {
            observation.evaluate {
                assertEquals(0, before.value)
                pause()
                assertEquals(0, after.value)
            }
            before.value = 1
            after.value = 1
            return invalidations
        } finally {
            observation.close()
        }
    }
}
