@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Verifies that nested evaluations restore dependency tracking and mutation guards after application failures.
 */
internal class StateObservationContextTest {
    @Test
    fun failedNestedEvaluationRestoresOuterTrackingAndReleasesItsMutationGuard() {
        val before = mutableStateOf(0)
        val inside = mutableStateOf(0)
        val after = mutableStateOf(0)
        var outerInvalidations = 0
        var innerInvalidations = 0
        val outer = StateObservation({}, {}, { outerInvalidations += 1 }, {})
        val inner = StateObservation({}, {}, { innerInvalidations += 1 }, {})
        val failure = IllegalStateException("Nested evaluation failed")
        try {
            outer.evaluate {
                assertEquals(0, before.value)
                assertSame(
                    failure,
                    assertFailsWith<IllegalStateException> {
                        inner.evaluate {
                            assertEquals(0, inside.value)
                            throw failure
                        }
                    },
                )
                assertFailsWith<IllegalStateException> { after.value = 1 }
                assertEquals(0, after.value)
            }
            before.value = 1
            assertEquals(1, outerInvalidations)
            assertEquals(0, innerInvalidations)
            inside.value = 1
            assertEquals(1, outerInvalidations)
            assertEquals(1, innerInvalidations)
            after.value = 1
            assertEquals(2, outerInvalidations)
            assertEquals(1, innerInvalidations)
        } finally {
            inner.close()
            outer.close()
        }
    }
}
