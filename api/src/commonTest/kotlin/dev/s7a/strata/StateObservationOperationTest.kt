@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Shared JVM/JavaScript operation ordering, owner nesting, failure and terminal cleanup contracts.
 */
internal class StateObservationOperationTest {
    @Test
    fun reentryAndOutOfOrderLeavePreserveOuterToInnerValidation() {
        val calls = ArrayList<Int>()
        val observations = List(3) { index -> StateObservation({}, {}, {}, { calls.add(index) }) }
        val state = mutableStateOf(0)
        try {
            observations.forEach(StateObservation::enterOperation)
            observations.forEach { observation ->
                assertEquals(
                    "A state observation operation is already active.",
                    assertFailsWith<IllegalStateException> { observation.enterOperation() }.message,
                )
            }
            assertEquals(
                "State observation operations must leave in reverse order.",
                assertFailsWith<IllegalStateException> { observations.first().leaveOperation() }.message,
            )
            state.value = 1
            assertEquals(listOf(0, 1, 2), calls)
            observations.last().leaveOperation()
            calls.clear()
            state.value = 2
            assertEquals(listOf(0, 1), calls)
            observations[1].leaveOperation()
            observations.first().leaveOperation()
            calls.clear()
            state.value = 3
            assertEquals(emptyList(), calls)
            assertEquals(
                "No state observation operation is active.",
                assertFailsWith<IllegalStateException> { observations.first().leaveOperation() }.message,
            )
            observations.first().enterOperation()
            observations.first().leaveOperation()
        } finally {
            observations.forEach(StateObservation::close)
        }
    }

    @Test
    fun throwingValidationKeepsTheValueAndFinallyReleasesEveryGuard() {
        val calls = ArrayList<Int>()
        val failure = IllegalStateException("Outer phase rejected the write")
        val outer = StateObservation({}, {}, {}, {
            calls.add(0)
            throw failure
        })
        val inner = StateObservation({}, {}, {}, { calls.add(1) })
        val state = mutableStateOf(0)
        try {
            outer.enterOperation()
            try {
                inner.enterOperation()
                try {
                    assertSame(failure, assertFailsWith<IllegalStateException> { state.value = 1 })
                    assertEquals(0, state.value)
                    assertEquals(listOf(0), calls)
                } finally {
                    inner.leaveOperation()
                }
            } finally {
                outer.leaveOperation()
            }
            state.value = 2
            assertEquals(2, state.value)
            assertEquals(listOf(0), calls)
        } finally {
            inner.close()
            outer.close()
        }
    }

    @Test
    fun closingNestedObservationsReleasesDependenciesButKeepsTheActivePhaseGuardUntilLeave() {
        val state = mutableStateOf(0)
        var invalidations = 0
        val failure = IllegalStateException("Cleanup phase rejected the write")
        val outer = StateObservation({}, {}, { invalidations += 1 }, { throw failure })
        val inner = outer.fork { invalidations += 1 }
        outer.evaluate { state.value }
        inner.evaluate { state.value }
        outer.enterOperation()
        try {
            inner.enterOperation()
            try {
                outer.close()
                assertSame(failure, assertFailsWith<IllegalStateException> { state.value = 1 })
                assertEquals(0, state.value)
            } finally {
                inner.leaveOperation()
            }
        } finally {
            outer.leaveOperation()
        }
        outer.close()
        inner.close()
        state.value = 2
        assertEquals(0, invalidations)
    }

    @Test
    fun deeplyNestedGuardsValidateIterativelyAndCanBeReusedAfterRelease() {
        var expected = 0
        val observations = List(4_096) { index ->
            StateObservation({}, {}, {}, {
                assertEquals(index, expected)
                expected += 1
            })
        }
        val state = mutableStateOf(0)
        try {
            repeat(2) { revision ->
                expected = 0
                observations.forEach(StateObservation::enterOperation)
                try {
                    state.value = revision + 1
                    assertEquals(observations.size, expected)
                } finally {
                    observations.asReversed().forEach(StateObservation::leaveOperation)
                }
            }
            state.value = 3
            assertEquals(observations.size, expected)
        } finally {
            observations.forEach(StateObservation::close)
        }
    }

    @Test
    fun nestedLogicalOwnerCannotBypassItsCallersGuard() {
        val outerOwner = RuntimeExecutionOwner()
        val innerOwner = RuntimeExecutionOwner()
        outerOwner.run {
            val identity = RuntimeExecutionOwner.current()
            var validations = 0
            val outer = StateObservation({}, {}, {}, {
                validations += 1
                check(RuntimeExecutionOwner.current() == identity)
            })
            val outerState = mutableStateOf(0)
            outer.enterOperation()
            try {
                innerOwner.run {
                    val inner = StateObservation({}, {}, {}, {})
                    val innerState = mutableStateOf(0)
                    inner.enterOperation()
                    try {
                        assertFailsWith<IllegalStateException> { innerState.value = 1 }
                        assertEquals(0, innerState.value)
                    } finally {
                        inner.close()
                        inner.leaveOperation()
                    }
                }
                outerState.value = 1
                assertEquals(2, validations)
            } finally {
                outer.close()
                outer.leaveOperation()
            }
        }
    }
}
