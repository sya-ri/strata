@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Checks normalization precedence, complete guarded equal attempts, dependencies and invocation-local observer results.
 */
internal class TextAreaMutationOrderTest {
    @Test
    fun invalidInputWinsBeforeEvaluationOperationAndEqualityGuardsWhileAcceptedEqualInputStillReachesThem() {
        val state = TextAreaState("A\n🙂", maxLength = 4)
        val initial = state.value
        val phaseFailure = IllegalStateException("Guarded frame or lifecycle phase")
        var invalidations = 0
        var validations = 0
        val observation =
            StateObservation({}, {}, { invalidations += 1 }, {
                validations += 1
                throw phaseFailure
            })
        try {
            observation.evaluate {
                assertSame(initial, state.value)
                assertFailsWith<IllegalArgumentException> { state.value = "A\n🙂\uD800" }
                for (input in listOf(initial, initial.toCharArray().concatToString(), "A\r\n🙂", "B")) {
                    assertFailsWith<IllegalStateException> { state.value = input }
                }
            }
            observation.enterOperation()
            try {
                assertFailsWith<IllegalArgumentException> { state.value = "AAAAA\uD800" }
                assertEquals(0, validations)
                for (input in listOf(initial, "A\r\n🙂", "B")) {
                    assertSame(phaseFailure, assertFailsWith<IllegalStateException> { state.value = input })
                }
                assertEquals(3, validations)
            } finally {
                observation.leaveOperation()
            }
            StateObservation.compare {
                assertFailsWith<IllegalArgumentException> { state.value = "\uD800" }
                assertFailsWith<IllegalStateException> { state.value = initial }
                true
            }
            assertSame(initial, state.value)
            assertEquals(0, invalidations)
            state.value = "B"
            assertEquals(1, invalidations)
        } finally {
            observation.close()
        }
    }

    @Test
    fun reentryFailureAndObserverReplacementKeepCompletePreviouslyCommittedValuesImmutable() {
        val state = TextAreaState("start")
        val scroll = state.scrollState
        val trace = mutableListOf<String>()
        val retained = mutableListOf<String>()
        val failure = IllegalStateException("Reentrant observer failure")
        val first = state.observe { committed ->
            assertSame(committed, state.value)
            trace.add(committed)
            retained.add(committed)
            if (trace.size == 1) {
                state.value = "B\r\n日"
                assertEquals("B\n日", state.value)
                assertEquals("A\n🙂", committed)
                throw failure
            }
        }
        assertSame(failure, assertFailsWith<IllegalStateException> { state.value = "A\u2029🙂" })
        assertEquals(listOf("A\n🙂", "B\n日"), trace)
        assertEquals(trace, retained)
        first.close()
        val second = state.observe { trace.add(it) }
        try {
            first.close()
            state.value = "B\u0085日"
            assertEquals(2, trace.size)
            state.value = "C\rD"
            assertEquals(listOf("A\n🙂", "B\n日", "C\nD"), trace)
            assertEquals(listOf("A\n🙂", "B\n日"), retained)
        } finally {
            second.close()
        }
        state.value = "detached"
        assertSame(scroll, state.scrollState)
        assertEquals(3, trace.size)
    }
}
