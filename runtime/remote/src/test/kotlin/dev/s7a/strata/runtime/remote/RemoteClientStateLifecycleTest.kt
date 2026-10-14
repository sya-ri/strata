@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Requires ordered edit polling, failure continuation and owner isolation independently of storage representation.
 */
internal class RemoteClientStateLifecycleTest {
    @Test
    fun recursionDoesNotRepollAndFailureRestoresTheFlushGuard() {
        var polls = 0
        var sequence = 0L
        val trace = mutableListOf<Long>()
        val failure = IllegalStateException("send failed")
        RemoteClientStates(RemoteLimits()).use { states ->
            val editable =
                RemoteEditableValue { actions ->
                    polls++
                    actions.send(1, TYPE, ProjectionValue.Absent)
                }
            states.update { states.prepare(1, EDITABLE, { editable }, {}) }
            val actions =
                RemoteClientActions { _, _, _ ->
                    states.flushEdits(NO_ACTIONS)
                    trace.add(++sequence)
                    sequence
                }
            states.flushEdits(actions)
            assertEquals(1, polls)
            assertEquals(listOf(1L), trace)
            assertSame(
                failure,
                assertThrows(IllegalStateException::class.java) {
                    states.flushEdits(RemoteClientActions { _, _, _ -> throw failure })
                },
            )
            states.flushEdits(actions)
            assertEquals(3, polls)
            assertEquals(listOf(1L, 2L), trace)
        }
    }

    @Test
    fun caughtRetirementFailureKeepsRemainingEditorsInOriginalOrder() {
        val states = RemoteClientStates(RemoteLimits())
        val failure = IllegalStateException("first retirement failed")
        val polls = mutableListOf<Int>()
        val releases = mutableListOf<Int>()
        val first = RemoteEditableValue { polls.add(1) }
        val shared = RemoteEditableValue { polls.add(2) }
        val newest = RemoteEditableValue { polls.add(4) }
        try {
            states.update {
                states.prepare(1, EDITABLE, { first }, {}, {
                    releases.add(1)
                    throw failure
                })
                states.prepare(2, EDITABLE, { shared }, {}, { releases.add(2) })
                states.prepare(3, PASSIVE, { Any() }, {}, { releases.add(3) })
                states.prepare(5, EDITABLE, { shared }, {}, { releases.add(5) })
            }
            assertSame(
                failure,
                assertThrows(IllegalStateException::class.java) {
                    states.update {
                        states.prepare(4, EDITABLE, { newest }, {}, { releases.add(4) })
                    }
                },
            )
            assertEquals(listOf(1), releases)
            states.flushEdits(NO_ACTIONS)
            assertEquals(listOf(2, 2, 4), polls)
        } finally {
            states.close()
        }
        assertEquals(listOf(1, 2, 3, 5, 4), releases)
        assertThrows(IllegalStateException::class.java) { states.flushEdits(NO_ACTIONS) }
    }

    @Test
    fun independentOwnersCannotPollMutateOrCloseEachOthersMembership() {
        val firstOwner = RuntimeExecutionOwner()
        val secondOwner = RuntimeExecutionOwner()
        var firstPolls = 0
        var secondPolls = 0
        val first = firstOwner.run { RemoteClientStates(RemoteLimits()) }
        val second = secondOwner.run { RemoteClientStates(RemoteLimits()) }
        try {
            firstOwner.run {
                first.update { first.prepare(1, EDITABLE, { RemoteEditableValue { firstPolls++ } }, {}) }
                assertThrows(IllegalStateException::class.java) { second.flushEdits(NO_ACTIONS) }
                assertThrows(IllegalStateException::class.java) { second.close() }
                first.flushEdits(NO_ACTIONS)
            }
            secondOwner.run {
                second.update { second.prepare(1, EDITABLE, { RemoteEditableValue { secondPolls++ } }, {}) }
                assertThrows(IllegalStateException::class.java) { first.update {} }
                second.flushEdits(NO_ACTIONS)
            }
            assertEquals(1, firstPolls)
            assertEquals(1, secondPolls)
        } finally {
            firstOwner.run { first.close() }
            secondOwner.run { second.close() }
        }
    }

    private companion object {
        val TYPE = ProjectionType(ResourceId("test", "editable"))
        val EDITABLE = RemoteStateKey(RemoteEditableValue::class)
        val PASSIVE = RemoteStateKey(Any::class)
        val NO_ACTIONS = RemoteClientActions { _, _, _ -> error("Unexpected action") }
    }
}
