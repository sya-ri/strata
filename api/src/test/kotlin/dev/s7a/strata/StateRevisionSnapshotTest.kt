package dev.s7a.strata

import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies revision value semantics and the shallow immutability of snapshot carriers.
 */
internal class StateRevisionSnapshotTest {
    @Test
    fun revisionRejectsNegativeAndOrdersZeroThroughMaximum() {
        assertThrows(IllegalArgumentException::class.java) { StateRevision(-1) }
        val zero = StateRevision(0)
        val maximum = StateRevision(Long.MAX_VALUE)

        assertTrue(zero < maximum)
        assertEquals(zero, StateRevision(0))
        assertEquals(zero.hashCode(), StateRevision(0).hashCode())
        assertEquals(maximum, StateRevision(Long.MAX_VALUE))
    }

    @Test
    fun snapshotEqualityIncludesRevisionAndValue() {
        val snapshot: StateSnapshot<Any> = StateSnapshot(StateRevision(3), "value")

        assertEquals(StateSnapshot(StateRevision(3), "value"), snapshot)
        assertEquals(snapshot.hashCode(), StateSnapshot(StateRevision(3), "value").hashCode())
        assertTrue(snapshot != StateSnapshot(StateRevision(4), "value"))
    }

    @Test
    fun snapshotCarrierDoesNotDeepFreezeMutableReferents() {
        val value = mutableListOf("before")
        val snapshot = StateSnapshot(StateRevision(0), value)

        value.add("after")

        assertEquals(listOf("before", "after"), snapshot.value)
    }

    @Test
    fun stateSourceAndSnapshotAreCovariant() {
        val stringSource: StateSource<String> =
            StateSource { _ ->
                StateSubscription(StateSnapshot(StateRevision(0), "value")) {}
            }
        val anySource: StateSource<Any> = stringSource
        val anySnapshot: StateSnapshot<Any> = StateSnapshot(StateRevision(0), "value")

        assertSame(stringSource, anySource)
        assertEquals("value", anySnapshot.value)
    }
}
