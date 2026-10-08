@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Requires an irreversible terminal cutoff and exactly-once ownership across trusted extension callback reentry.
 * This hazard predates the editable index; every case observes both complete and editable membership.
 */
@Suppress("TooManyFunctions") // One lifecycle matrix covers creation, updates, retirement, polling and their failure/reentry boundaries.
internal class RemoteClientStateReentryTest {
    @Test
    fun closingFromFactoryReleasesItsReturnedValueWithoutAdmissionOrResurrection() {
        val states = RemoteClientStates(RemoteLimits())
        val value = Owned()
        assertThrows(IllegalStateException::class.java) {
            states.update {
                states.prepare(1, KEY, {
                    states.close()
                    value
                }, {}, Owned::release)
            }
        }
        assertEquals(1, value.releases)
        assertClosed(states)
        states.close()
        assertEquals(1, value.releases)
    }

    @Test
    fun closingFromUpdateReleasesTheCurrentValueOnceAndNeverRestoresIdle() {
        val states = RemoteClientStates(RemoteLimits())
        val value = Owned()
        states.update { prepare(states, 1, value) }
        assertThrows(IllegalStateException::class.java) {
            states.update {
                states.prepare(1, KEY, { error("Must reuse the value") }, { states.close() }, Owned::release)
            }
        }
        assertEquals(1, value.releases)
        assertClosed(states)
    }

    @Test
    fun closingThePreparationPhaseWithoutAnEntryCannotRestoreIdle() {
        val states = RemoteClientStates(RemoteLimits())
        assertThrows(IllegalStateException::class.java) { states.update { states.close() } }
        assertClosed(states)
    }

    @Test
    fun closingFromRetirementReleasesEveryRemainingEntryOnceInOrder() {
        val states = RemoteClientStates(RemoteLimits())
        val released = mutableListOf<Int>()
        val first =
            Owned {
                released.add(1)
                states.close()
            }
        val second = Owned { released.add(2) }
        val third = Owned { released.add(3) }
        states.update {
            prepare(states, 1, first)
            prepare(states, 2, second)
            prepare(states, 3, third)
        }
        assertThrows(IllegalStateException::class.java) { states.update {} }
        assertEquals(listOf(1, 2, 3), released)
        assertEquals(listOf(1, 1, 1), listOf(first.releases, second.releases, third.releases))
        assertClosed(states)
    }

    @Test
    fun closingFromPollingStopsBeforeAnyReleasedSiblingIsVisited() {
        val states = RemoteClientStates(RemoteLimits())
        val first = Owned()
        first.onPoll = { states.close() }
        val second = Owned()
        states.update {
            prepare(states, 1, first)
            prepare(states, 2, second)
        }
        assertThrows(IllegalStateException::class.java) { states.flushEdits(NO_ACTIONS) }
        assertEquals(1, first.polls)
        assertEquals(0, second.polls)
        assertEquals(listOf(1, 1), listOf(first.releases, second.releases))
        assertClosed(states)
    }

    @Test
    fun nestedFactoryPreparationIsRejectedBeforeCreatingEitherAddress() {
        for (address in listOf(1L, 2L)) {
            RemoteClientStates(RemoteLimits()).use { states ->
                val value = Owned()
                var nestedCreates = 0
                states.update {
                    states.prepare(1, KEY, {
                        assertThrows(IllegalStateException::class.java) {
                            states.prepare(address, KEY, {
                                nestedCreates++
                                Owned()
                            }, {}, Owned::release)
                        }
                        value
                    }, {}, Owned::release)
                }
                assertSame(value, states.get(1, KEY))
                assertEquals(0, nestedCreates)
                assertEquals(1, entries(states).size)
                states.flushEdits(NO_ACTIONS)
                assertEquals(1, value.polls)
            }
        }
    }

    @Test
    fun nestedUpdatePreparationIsRejectedBeforeCreatingOrUpdatingEitherAddress() {
        for (address in listOf(1L, 2L)) {
            RemoteClientStates(RemoteLimits()).use { states ->
                val value = Owned()
                var nestedCallbacks = 0
                states.update { prepare(states, 1, value) }
                states.update {
                    states.prepare(1, KEY, { error("Must reuse") }, {
                        assertThrows(IllegalStateException::class.java) {
                            states.prepare(address, KEY, {
                                nestedCallbacks++
                                Owned()
                            }, { nestedCallbacks++ }, Owned::release)
                        }
                    })
                }
                assertSame(value, states.get(1, KEY))
                assertEquals(0, nestedCallbacks)
                assertEquals(1, entries(states).size)
            }
        }
    }

    @Test
    fun retirementCannotReadmitTheRemovedAddressOrAnotherAddress() {
        for (address in listOf(1L, 2L)) {
            RemoteClientStates(RemoteLimits()).use { states ->
                var nestedCreates = 0
                val value =
                    Owned {
                        assertThrows(IllegalStateException::class.java) {
                            states.prepare(address, KEY, {
                                nestedCreates++
                                Owned()
                            }, {}, Owned::release)
                        }
                        assertThrows(IllegalStateException::class.java) { states.update {} }
                    }
                states.update { prepare(states, 1, value) }
                states.update {}
                assertEquals(0, nestedCreates)
                assertEquals(1, value.releases)
                assertTrue(entries(states).isEmpty())
                assertTrue(editable(states).isEmpty())
                states.flushEdits(NO_ACTIONS)
                assertEquals(0, value.polls)
            }
        }
    }

    @Test
    fun pollingCannotStartPreparationButRecursiveFlushStillReturnsWithoutRepolling() {
        RemoteClientStates(RemoteLimits()).use { states ->
            val first = Owned()
            var preparations = 0
            first.onPoll = {
                states.flushEdits(NO_ACTIONS)
                assertThrows(IllegalStateException::class.java) { states.update { preparations++ } }
            }
            val second = Owned()
            states.update {
                prepare(states, 1, first)
                prepare(states, 2, second)
            }
            states.flushEdits(NO_ACTIONS)
            assertEquals(0, preparations)
            assertEquals(listOf(1, 1), listOf(first.polls, second.polls))
            assertEquals(2, entries(states).size)
        }
    }

    @Test
    fun throwingFreshResultReleaseRemainsPrimaryAfterFactoryCloses() {
        val states = RemoteClientStates(RemoteLimits())
        val failure = IllegalArgumentException("fresh release failed")
        val value = Owned { throw failure }
        assertSame(
            failure,
            assertThrows(IllegalArgumentException::class.java) {
                states.update {
                    states.prepare(1, KEY, {
                        states.close()
                        value
                    }, {}, Owned::release)
                }
            },
        )
        assertEquals(1, value.releases)
        assertClosed(states)
    }

    @Test
    fun cleanupFailureDuringUpdateCloseKeepsTheFirstFailureAndSuppressionOrder() {
        val states = RemoteClientStates(RemoteLimits())
        val failure = IllegalStateException("first cleanup failure")
        val later = IllegalArgumentException("later cleanup failure")
        val first = Owned { throw failure }
        val second = Owned { throw later }
        states.update {
            prepare(states, 1, first)
            prepare(states, 2, second)
        }
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                states.update { states.prepare(1, KEY, { error("Must reuse") }, { states.close() }) }
            },
        )
        assertEquals(listOf(later), failure.suppressed.toList())
        assertEquals(listOf(1, 1), listOf(first.releases, second.releases))
        assertClosed(states)
    }

    @Test
    fun recursiveCloseFromReleaseIsIdempotentAndOrdinaryPreparationRemainsUsableUntilClose() {
        val states = RemoteClientStates(RemoteLimits())
        val first = Owned { states.close() }
        val second = Owned()
        states.update { prepare(states, 1, first) }
        states.update {
            prepare(states, 1, first)
            prepare(states, 2, second)
        }
        assertSame(first, states.get(1, KEY))
        assertSame(second, states.get(2, KEY))
        states.flushEdits(NO_ACTIONS)
        assertEquals(listOf(1, 1), listOf(first.polls, second.polls))
        states.close()
        assertEquals(listOf(1, 1), listOf(first.releases, second.releases))
        assertClosed(states)
    }

    @Test
    fun aFactoryCannotCloseAnotherOwnersStoreOrAdmitUnderAnotherOwner() {
        val firstOwner = RuntimeExecutionOwner()
        val secondOwner = RuntimeExecutionOwner()
        val first = firstOwner.run { RemoteClientStates(RemoteLimits()) }
        val second = secondOwner.run { RemoteClientStates(RemoteLimits()) }
        val value = Owned()
        val other = Owned()
        try {
            firstOwner.run {
                first.update {
                    first.prepare(1, KEY, {
                        assertThrows(IllegalStateException::class.java) { second.close() }
                        secondOwner.run {
                            assertThrows(IllegalStateException::class.java) {
                                first.prepare(2, KEY, { error("Must not create") }, {})
                            }
                        }
                        value
                    }, {}, Owned::release)
                }
                assertSame(value, first.get(1, KEY))
            }
            secondOwner.run {
                second.update { prepare(second, 1, other) }
                assertSame(other, second.get(1, KEY))
            }
        } finally {
            firstOwner.run { first.close() }
            secondOwner.run { second.close() }
        }
        assertEquals(listOf(1, 1), listOf(value.releases, other.releases))
    }

    @Test
    fun callbackFailuresAfterCloseRemainPrimaryAcrossEveryBoundary() {
        for (boundary in Boundary.entries) {
            val states = RemoteClientStates(RemoteLimits())
            val failure = IllegalArgumentException("callback failed after close")
            val closeAndFail = {
                states.close()
                throw failure
            }
            val first =
                Owned {
                    if (boundary == Boundary.Retirement) closeAndFail()
                }
            val second = Owned()
            first.onPoll = {
                if (boundary == Boundary.Polling) closeAndFail()
            }
            states.update {
                prepare(states, 1, first)
                prepare(states, 2, second)
            }
            assertSame(
                failure,
                assertThrows(IllegalArgumentException::class.java) {
                    when (boundary) {
                        Boundary.Creation -> states.update { states.prepare(3, KEY, closeAndFail, {}, Owned::release) }
                        Boundary.Update -> states.update { states.prepare(1, KEY, { error("Must reuse") }, { closeAndFail() }) }
                        Boundary.Retirement -> states.update {}
                        Boundary.Polling -> states.flushEdits(NO_ACTIONS)
                    }
                },
            )
            assertEquals(listOf(1, 1), listOf(first.releases, second.releases))
            assertClosed(states)
        }
    }

    private fun prepare(
        states: RemoteClientStates,
        identity: Long,
        value: Owned,
    ) {
        states.prepare(identity, KEY, { value }, {}, Owned::release)
    }

    private fun assertClosed(states: RemoteClientStates) {
        assertTrue(entries(states).isEmpty())
        assertTrue(editable(states).isEmpty())
        assertThrows(IllegalStateException::class.java) { states.get(1, KEY) }
        assertThrows(IllegalStateException::class.java) { states.update {} }
        assertThrows(IllegalStateException::class.java) { states.prepare(1, KEY, { error("Must not create") }, {}) }
        assertThrows(IllegalStateException::class.java) { states.flushEdits(NO_ACTIONS) }
        states.close()
    }

    private fun entries(states: RemoteClientStates): Map<*, *> {
        val field = RemoteClientStates::class.java.getDeclaredField("values")
        field.isAccessible = true
        return field.get(states) as Map<*, *>
    }

    private fun editable(states: RemoteClientStates): Map<*, *> {
        val field = RemoteClientStates::class.java.getDeclaredField("editable")
        field.isAccessible = true
        return field.get(states) as Map<*, *>
    }

    private class Owned(
        private val onRelease: () -> Unit = {},
    ) : RemoteEditableValue {
        var releases = 0
        var polls = 0
        var onPoll: (RemoteClientActions) -> Unit = {}

        override fun flushEdits(actions: RemoteClientActions) {
            polls++
            onPoll(actions)
        }

        fun release() {
            releases++
            onRelease()
        }
    }

    private enum class Boundary { Creation, Update, Retirement, Polling }

    private companion object {
        val KEY = RemoteStateKey(Owned::class)
        val NO_ACTIONS = RemoteClientActions { _, _, _ -> error("Unexpected action") }
    }
}
