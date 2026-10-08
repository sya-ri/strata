@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import dev.s7a.strata.state.mutableStateOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent ordered reference snapshots for assignment guards, arbitrary equality and captured observer membership.
 */
internal class MutableStateRoutingTest {
    @Test
    fun equalAndUnequalAttemptsKeepFirstOccurrenceOrderAndDuplicateReadsDoNotChangeMembership() {
        Fixture().use { fixture ->
            fixture.add(Owner.A)
            fixture.add(Owner.B)
            fixture.add(Owner.A)
            fixture.verify(Tone.First)
            fixture.verify(Tone.Second)
            fixture.members.forEach { entry -> entry.observation.evaluate { repeat(4) { fixture.state.value } } }
            fixture.verify(Tone.Second)
            fixture.verify(Tone.First)
        }
    }

    @Test
    fun removingTheFirstObserverReversesTheNextOwnerOrderAndReadmissionMovesItToTheEnd() {
        Fixture().use { fixture ->
            val first = fixture.add(Owner.A)
            fixture.add(Owner.B)
            fixture.add(Owner.A)
            fixture.verify(Tone.First)
            fixture.remove(first)
            fixture.verify(Tone.Second)
            fixture.add(Owner.A)
            fixture.verify(Tone.First)
            assertEquals(listOf(Owner.B, Owner.A), fixture.members.map { it.owner }.distinct())
        }
    }

    @Test
    fun aFailureAtEveryGuardPositionLeavesOnlyTheSuccessfulPrefixInReverseOrder() {
        for (failing in Owner.entries) {
            Fixture().use { fixture ->
                Owner.entries.forEach(fixture::add)
                fixture.failingOwner = failing
                val failure = fixture.guardFailure
                fixture.verify(Tone.Second, failure)
                assertEquals(Tone.First, fixture.state.value.tone)
                assertTrue(fixture.entered.isEmpty())
                fixture.failingOwner = null
                fixture.verify(Tone.Second)
            }
        }
    }

    @Test
    fun throwingEqualityKeepsThePreviousReferenceAndReleasesAllGuards() {
        Fixture().use { fixture ->
            Owner.entries.forEach(fixture::add)
            val previous = fixture.state.value
            fixture.failEquality = true
            fixture.verify(Tone.Second, fixture.equalityFailure)
            assertSame(previous, fixture.state.value)
            assertTrue(fixture.entered.isEmpty())
            fixture.failEquality = false
            fixture.verify(Tone.Second)
        }
    }

    @Test
    fun removalDuringGuardEntryKeepsTheCapturedInvalidationAndAffectsOnlyTheNextAttempt() {
        Fixture().use { fixture ->
            val first = fixture.add(Owner.A)
            fixture.add(Owner.B)
            fixture.add(Owner.A)
            fixture.beforeBegin = { owner ->
                if (owner == Owner.A) {
                    fixture.beforeBegin = null
                    fixture.remove(first)
                }
            }
            fixture.verify(Tone.Second)
            fixture.verify(Tone.First)
        }
    }

    @Test
    fun admissionDuringGuardEntryWaitsForTheFollowingCapturedMembership() {
        Fixture().use { fixture ->
            fixture.add(Owner.A)
            fixture.add(Owner.B)
            fixture.beforeBegin = { owner ->
                if (owner == Owner.A) {
                    fixture.beforeBegin = null
                    fixture.add(Owner.C)
                }
            }
            fixture.verify(Tone.Second)
            fixture.verify(Tone.First)
        }
    }

    @Test
    fun closingAnOwnerDuringEqualityStillInvalidatesItsCapturedObservers() {
        Fixture().use { fixture ->
            fixture.add(Owner.A)
            fixture.add(Owner.B)
            fixture.add(Owner.A)
            fixture.duringEquality = {
                fixture.duringEquality = null
                fixture.closeOwner(Owner.A)
            }
            fixture.verify(Tone.Second)
            fixture.verify(Tone.First)
        }
    }

    @Test
    fun failedEvaluationKeepsNewDependenciesUntilCloseAndSuccessfulReplacementForgetsOldOnes() {
        val first = mutableStateOf(0)
        val second = mutableStateOf(0)
        var changes = 0
        val observation = StateObservation({}, {}, { changes += 1 }, {})
        val failure = IllegalStateException("Evaluation failed")
        try {
            observation.evaluate { first.value }
            first.value = 0
            val caught =
                assertFailsWith<IllegalStateException> {
                    observation.evaluate {
                        second.value
                        throw failure
                    }
                }
            assertSame(failure, caught)
            first.value = 1
            second.value = 1
            assertEquals(2, changes)
            observation.evaluate { second.value }
            first.value = 2
            assertEquals(2, changes)
            second.value = 2
            assertEquals(3, changes)
        } finally {
            observation.close()
        }
        first.value = 3
        second.value = 3
        assertEquals(3, changes)
    }

    @Test
    fun equalityDirectionAndIdenticalReferenceAttemptsAlwaysInvokeTheCurrentValue() {
        var comparisons = 0
        val first = Equality {
            comparisons += 1
            true
        }
        val next = Equality { error("The next value must not own comparison") }
        val state = mutableStateOf(first)
        state.value = first
        state.value = next
        assertEquals(2, comparisons)
        assertSame(first, state.value)
    }

    @Test
    fun unobservedAndWarmEqualWritesStillValidateCurrentOperationsBeforeComparison() {
        val state = mutableStateOf(0)
        val validations = ArrayList<Owner>()
        var allowed = true
        val first = StateObservation({}, {}, {}, { validations.add(Owner.A) })
        val second =
            StateObservation({}, {}, {}, {
                validations.add(Owner.B)
                check(allowed)
            })
        val observed = first.fork {}
        observed.evaluate { state.value }
        state.value = 0
        first.enterOperation()
        second.enterOperation()
        try {
            state.value = 0
            assertEquals(listOf(Owner.A, Owner.B), validations)
            validations.clear()
            allowed = false
            assertFailsWith<IllegalStateException> { state.value = 0 }
            assertEquals(listOf(Owner.A, Owner.B), validations)
            observed.close()
            validations.clear()
            assertFailsWith<IllegalStateException> { state.value = 1 }
            assertEquals(listOf(Owner.A, Owner.B), validations)
        } finally {
            second.leaveOperation()
            first.leaveOperation()
            second.close()
            first.close()
        }
        state.value = 1
        assertEquals(1, state.value)
    }

    @Test
    fun equalityCannotReadWriteEvaluateOrReenterAnotherStateAndFailureRestoresTheCaller() {
        val other = mutableStateOf(0)
        val observer = StateObservation({}, {}, {}, {})
        var comparisons = 0
        val state = mutableStateOf(Equality {
            comparisons += 1
            assertFailsWith<IllegalStateException> { other.value }
            assertFailsWith<IllegalStateException> { other.value = 1 }
            assertFailsWith<IllegalStateException> { observer.evaluate { other.value } }
            false
        })
        try {
            observer.evaluate { state.value }
            state.value = Equality { true }
            assertEquals(1, comparisons)
            other.value = 2
            assertEquals(2, other.value)
        } finally {
            observer.close()
        }
    }

    /**
     * Distinct root identities used by the independent model; membership order comes from entry admission.
     */
    private enum class Owner {
        A,
        B,
        C,
    }

    /**
     * Prepared caller values; comparison never hashes or consults routing metadata.
     */
    private enum class Tone {
        First,
        Second,
    }

    /**
     * Observable attempt phases, independent of private plan construction or cache state.
     */
    private enum class Phase {
        Begin,
        Compare,
        Invalidate,
        End,
    }

    /**
     * Literal trace entry; invalidations also prove that the new value was committed first.
     */
    private data class Event(
        val phase: Phase,
        val owner: Owner? = null,
        val observer: Int? = null,
        val committed: Tone? = null,
    )

    /**
     * Original caller equality receiver with an explicit comparison hook.
     */
    private class Value(
        val tone: Tone,
        private val onCompare: () -> Unit,
    ) {
        override fun equals(other: Any?): Boolean {
            onCompare()
            return other is Value && tone == other.tone
        }

        override fun hashCode(): Int = error("State value hashing is outside the assignment contract")
    }

    /**
     * Direct equality operand probe, without an observation-plan query.
     */
    private class Equality(
        private val compare: () -> Boolean,
    ) {
        override fun equals(other: Any?): Boolean = other is Equality && compare()

        override fun hashCode(): Int = 0
    }

    /**
     * Independent membership entry; owner tags are assigned by construction rather than candidate projection.
     */
    private data class Entry(
        val index: Int,
        val owner: Owner,
        val observation: StateObservation,
    )

    /**
     * Owner-confined trace fixture with baseline-style per-attempt snapshots and first-occurrence projection.
     */
    private class Fixture : AutoCloseable {
        val events = ArrayList<Event>()
        val members = ArrayList<Entry>()
        val entered = LinkedHashSet<Owner>()
        val guardFailure = IllegalArgumentException("Guard failed")
        val equalityFailure = IllegalStateException("Equality failed")
        var failingOwner: Owner? = null
        var failEquality = false
        var beforeBegin: ((Owner) -> Unit)? = null
        var duringEquality: (() -> Unit)? = null
        private var nextIndex = 0
        val state = mutableStateOf(Value(Tone.First, ::compared))
        private val roots =
            Owner.entries.associateWith { owner ->
                StateObservation(
                    {
                        events.add(Event(Phase.Begin, owner))
                        beforeBegin?.invoke(owner)
                        if (failingOwner == owner) throw guardFailure
                        check(entered.add(owner))
                    },
                    {
                        check(entered.remove(owner))
                        events.add(Event(Phase.End, owner))
                    },
                    {},
                    {},
                )
            }

        fun add(owner: Owner): Entry {
            val index = nextIndex++
            val observation = roots.getValue(owner).fork {
                events.add(Event(Phase.Invalidate, owner, index, state.value.tone))
            }
            observation.evaluate { state.value }
            return Entry(index, owner, observation).also(members::add)
        }

        fun remove(entry: Entry) {
            check(members.remove(entry))
            entry.observation.close()
        }

        fun closeOwner(owner: Owner) {
            members.removeAll { it.owner == owner }
            roots.getValue(owner).close()
        }

        fun verify(
            next: Tone,
            failure: Throwable? = null,
        ) {
            // This model snapshots actual declared entries before callbacks; it never reads the candidate's private plan.
            val captured = members.toList()
            val owners = ArrayList<Owner>()
            for (entry in captured) {
                if ((entry.owner in owners).not()) owners.add(entry.owner)
            }
            val previous = state.value
            val expected = ArrayList<Event>()
            val successful = ArrayList<Owner>()
            for (owner in owners) {
                expected.add(Event(Phase.Begin, owner))
                if (failingOwner == owner) break
                successful.add(owner)
            }
            if (failingOwner == null) {
                expected.add(Event(Phase.Compare))
                if (failEquality.not() && previous.tone != next) {
                    captured.forEach { expected.add(Event(Phase.Invalidate, it.owner, it.index, next)) }
                }
            }
            successful.asReversed().forEach { expected.add(Event(Phase.End, it)) }
            events.clear()
            val replacement = Value(next, ::compared)
            if (failure == null) {
                state.value = replacement
                assertEquals(next, state.value.tone)
                if (previous.tone == next) assertSame(previous, state.value)
            } else {
                assertSame(failure, assertFailsWith<Throwable> { state.value = replacement })
                assertSame(previous, state.value)
            }
            assertEquals(expected, events)
            assertTrue(entered.isEmpty())
        }

        private fun compared() {
            events.add(Event(Phase.Compare))
            duringEquality?.invoke()
            if (failEquality) throw equalityFailure
        }

        override fun close() {
            roots.values.forEach(StateObservation::close)
            members.clear()
            check(entered.isEmpty())
        }
    }
}
