package dev.s7a.strata.runtime.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Requires current editable-index membership, per-key retention and terminal reference release.
 */
internal class RemoteClientStatesTest {
    @Test
    fun pollingPreservesAdmissionOrderAndDuplicateValuesAcrossKeys() {
        val trace = mutableListOf<Int>()
        val first = RemoteEditableValue { trace.add(1) }
        val second = RemoteEditableValue { trace.add(2) }
        RemoteClientStates(RemoteLimits()).use { states ->
            states.update {
                states.prepare(1, PASSIVE, ::Any, {})
                states.prepare(2, EDITABLE, { first }, {})
                states.prepare(3, EDITABLE, { second }, {})
                states.prepare(4, EDITABLE, { first }, {})
            }
            repeat(2) { states.flushEdits(NO_ACTIONS) }
            assertEquals(listOf(1, 2, 1, 1, 2, 1), trace)
            assertEquals(listOf(first, second, first), membership(states))
            states.update {
                states.prepare(4, EDITABLE, { error("Must retain the value") }, {})
                states.prepare(2, EDITABLE, { error("Must retain the value") }, {})
                states.prepare(3, EDITABLE, { error("Must retain the value") }, {})
            }
            trace.clear()
            states.flushEdits(NO_ACTIONS)
            assertEquals(listOf(1, 2, 1), trace)
            val otherToken = RemoteStateKey(RemoteEditableValue::class)
            states.update {
                states.prepare(2, EDITABLE, { error("Must retain") }, {})
                states.prepare(2, otherToken, { first }, {})
            }
            trace.clear()
            states.flushEdits(NO_ACTIONS)
            assertEquals(listOf(1, 1), trace)
            assertEquals(2, membership(states).size)
        }
    }

    @Test
    fun updatesRemovalAndReadmissionReplaceOnlyTheRetiredMembership() {
        val first = RemoteEditableValue {}
        val next = RemoteEditableValue {}
        var updates = 0
        var releases = 0
        RemoteClientStates(RemoteLimits()).use { states ->
            states.update {
                states.prepare(1, EDITABLE, { first }, { updates++ }, {
                    assertFalse(membership(states).contains(first))
                    releases++
                })
            }
            states.update { states.prepare(1, EDITABLE, { error("Must retain") }, { updates++ }) }
            assertSame(first, states.get(1, EDITABLE))
            assertEquals(2, updates)
            states.update {}
            assertTrue(membership(states).isEmpty())
            assertEquals(1, releases)
            states.update { states.prepare(1, EDITABLE, { next }, {}) }
            assertEquals(listOf(next), membership(states))
        }
    }

    @Test
    fun terminalCleanupDropsEveryReferenceBeforeThrowingReleaseAndAttemptsAllCallbacks() {
        val failure = IllegalStateException("first release")
        val later = IllegalArgumentException("later release")
        val releases = mutableListOf<Int>()
        val states = RemoteClientStates(RemoteLimits())
        states.update {
            for (index in 1..3) {
                states.prepare(index.toLong(), EDITABLE, { RemoteEditableValue {} }, {}, {
                    assertTrue(membership(states).isEmpty())
                    assertTrue(entries(states).isEmpty())
                    releases.add(index)
                    when (index) {
                        1 -> throw failure
                        2 -> throw later
                    }
                })
            }
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { states.close() })
        assertEquals(listOf(1, 2, 3), releases)
        assertEquals(listOf(later), failure.suppressed.toList())
        states.close()
        assertEquals(3, releases.size)
        assertThrows(IllegalStateException::class.java) { states.flushEdits(NO_ACTIONS) }
    }

    @Test
    fun retirementFailureRemovesTheFailedValueBeforeTerminalCleanup() {
        val failure = IllegalStateException("retirement failed")
        val releases = mutableListOf<Int>()
        val states = RemoteClientStates(RemoteLimits())
        val first = RemoteEditableValue {}
        states.update {
            states.prepare(1, EDITABLE, { first }, {}, {
                assertFalse(membership(states).contains(first))
                releases.add(1)
                throw failure
            })
            states.prepare(2, EDITABLE, { RemoteEditableValue {} }, {}, { releases.add(2) })
        }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { states.update {} })
        states.close()
        assertEquals(listOf(1, 2), releases)
        assertTrue(membership(states).isEmpty())
        assertTrue(entries(states).isEmpty())
    }

    @Test
    fun repeatedChurnRetainsOnlyCurrentEntriesAndNeverHistoricalValues() {
        RemoteClientStates(RemoteLimits()).use { states ->
            repeat(1_000) { generation ->
                states.update {
                    states.prepare(generation.toLong() + 1, EDITABLE, { RemoteEditableValue {} }, {})
                }
                assertEquals(1, entries(states).size)
                assertEquals(1, membership(states).size)
            }
            states.update {}
            assertTrue(membership(states).isEmpty())
            assertTrue(entries(states).isEmpty())
        }
    }

    private fun entries(states: RemoteClientStates): Map<*, *> {
        val field = RemoteClientStates::class.java.getDeclaredField("values")
        field.isAccessible = true
        return field.get(states) as Map<*, *>
    }

    private fun membership(states: RemoteClientStates): List<*> {
        val field =
            RemoteClientStates::class.java
                .getDeclaredField("editable")
                .apply { isAccessible = true }
        return when (val members = field.get(states)) {
            is Map<*, *> -> {
                members.values.toList()
            }

            is List<*> -> {
                members.map { entry ->
                    val member = checkNotNull(entry)
                    val value = checkNotNull(member.javaClass.superclass).getDeclaredField("value")
                    value.isAccessible = true
                    value.get(member)
                }
            }

            else -> {
                error("Unsupported membership collection")
            }
        }
    }

    private companion object {
        val EDITABLE = RemoteStateKey(RemoteEditableValue::class)
        val PASSIVE = RemoteStateKey(Any::class)
        val NO_ACTIONS = RemoteClientActions { _, _, _ -> error("Unexpected action") }
    }
}
