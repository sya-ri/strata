@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.ComponentStateSubscription
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.ObservableComponentState
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Freezes standard component publication independently of the notification storage on both targets.
 */
internal class ComponentStatePublicationTest {
    @Test
    fun stableObserversReceiveCommittedValuesInRegistrationOrder() {
        val state = CheckboxState()
        val events = ArrayList<Pair<Int, Boolean>>()
        val handles =
            List(16) { index ->
                state.observe { value ->
                    assertEquals(value, state.checked)
                    events.add(index to value)
                }
            }
        for (value in listOf(true, false, true)) {
            state.checked = value
            assertEquals(List(16) { it to value }, events)
            events.clear()
            state.checked = value
            assertEquals(emptyList(), events)
        }
        handles.forEach(ComponentStateSubscription::close)
        state.checked = false
        assertEquals(emptyList(), events)
    }

    @Test
    fun removalAndAdditionAffectTheNextPublicationButNotTheCurrentSnapshot() {
        val state = CheckboxState()
        val events = ArrayList<Int>()
        var first: ComponentStateSubscription? = null
        var later: ComponentStateSubscription? = null
        var added: ComponentStateSubscription? = null
        first =
            state.observe {
                events.add(1)
                first?.close()
                later?.close()
                added = state.observe { events.add(4) }
            }
        later = state.observe { events.add(2) }
        val last = state.observe { events.add(3) }

        state.checked = true
        assertEquals(listOf(1, 2, 3), events)
        events.clear()
        state.checked = false
        assertEquals(listOf(3, 4), events)
        first.close()
        later.close()
        last.close()
        added?.close()
    }

    @Test
    fun nestedWriteUsesCurrentMembershipAndOuterDeliveryRetainsItsOriginalValue() {
        val state = CheckboxState()
        val events = ArrayList<Pair<Int, Boolean>>()
        var nested = false
        var later: ComponentStateSubscription? = null
        var added: ComponentStateSubscription? = null
        val first =
            state.observe { value ->
                events.add(1 to value)
                if (nested.not()) {
                    nested = true
                    later?.close()
                    added = state.observe { events.add(3 to it) }
                    state.checked = false
                }
            }
        later = state.observe { events.add(2 to it) }
        state.checked = true
        assertEquals(listOf(1 to true, 1 to false, 3 to false, 2 to true), events)
        assertEquals(false, state.checked)
        events.clear()
        state.checked = true
        assertEquals(listOf(1 to true, 3 to true), events)
        first.close()
        later.close()
        added?.close()
    }

    @Test
    fun observerFailureStopsDeliveryAfterCommitAndLaterWritesRecover() {
        val state = CheckboxState()
        val failure = IllegalArgumentException("observer")
        val events = ArrayList<Int>()
        val first = state.observe { events.add(1) }
        val failing = state.observe { throw failure }
        val last = state.observe { events.add(3) }
        assertSame(failure, assertFailsWith<IllegalArgumentException> { state.checked = true })
        assertEquals(true, state.checked)
        assertEquals(listOf(1), events)
        state.checked = true
        assertEquals(listOf(1), events)
        failing.close()
        state.checked = false
        assertEquals(listOf(1, 1, 3), events)
        first.close()
        last.close()
    }

    @Test
    fun duplicateRejectionAndReRegistrationPreserveSetMembershipAndOrder() {
        val state = CheckboxState()
        val events = ArrayList<Int>()
        val callback: (Boolean) -> Unit = { events.add(1) }
        val first = state.observe(callback)
        val last = state.observe { events.add(2) }
        assertFailsWith<IllegalStateException> { state.observe(callback) }
        state.checked = true
        assertEquals(listOf(1, 2), events)
        first.close()
        first.close()
        val replacement = state.observe(callback)
        events.clear()
        first.close()
        state.checked = false
        assertEquals(listOf(2, 1), events)
        last.close()
        replacement.close()
    }

    @Test
    fun declarativeInvalidationCanRegisterBeforeTheNotificationSnapshotIsSelected() {
        val state = CheckboxState()
        val events = ArrayList<Int>()
        val original = state.observe { events.add(1) }
        var added: ComponentStateSubscription? = null
        val observation = StateObservation({}, {}, { added = state.observe { events.add(2) } }, {})
        observation.evaluate { state.checked }
        state.checked = true
        assertEquals(listOf(1, 2), events)
        observation.close()
        original.close()
        added?.close()
    }

    @Test
    fun validationFailureDoesNotCommitOrNotify() {
        val failure = IllegalArgumentException("validation")
        val state = ObservableComponentState(0) { if (it < 0) throw failure }
        val values = ArrayList<Int>()
        val handle = state.observe { values.add(it) }
        state.set(1)
        assertSame(failure, assertFailsWith<IllegalArgumentException> { state.set(-1) })
        assertEquals(1, state.get())
        assertEquals(listOf(1), values)
        state.set(2)
        assertEquals(listOf(1, 2), values)
        handle.close()
    }

    @Test
    fun equalityFailurePreservesTheValueAndSubsequentNotification() {
        val initial = Value(0)
        val state = ObservableComponentState(initial) { _ -> }
        val values = ArrayList<Value>()
        val handle = state.observe { values.add(it) }
        val next = Value(1)
        state.set(next)
        val failure = IllegalStateException("equality")
        next.failure = failure
        assertSame(failure, assertFailsWith<IllegalStateException> { state.set(Value(2)) })
        assertSame(next, state.get())
        assertEquals(1, values.size)
        next.failure = null
        val last = Value(3)
        state.set(last)
        assertEquals(listOf(next, last), values)
        handle.close()
    }

    @Test
    fun everyStandardStateNotifiesOnlyDistinctNormalizedValues() {
        val slider = SliderState(0.0, steps = 1)
        val cycle = CycleButtonState(listOf(0, 1), 0)
        val text = TextFieldState()
        val area = TextAreaState()
        val values = ArrayList<Any>()
        val handles =
            listOf(
                slider.observe { values.add(it) },
                cycle.observe { values.add(it) },
                text.observe { values.add(it) },
                area.observe { values.add(it) },
            )
        slider.value = 0.4
        slider.value = 0.6
        cycle.value = 1
        cycle.value = 1
        text.value = "text"
        text.value = "text"
        area.value = "a\r\nb"
        area.value = "a\nb"
        assertEquals(listOf<Any>(0.5, 1, "text", "a\nb"), values)
        handles.forEach { it.close() }
    }

    private class Value(
        private val id: Int,
    ) {
        var failure: Throwable? = null

        @Suppress("ExceptionRaisedInUnexpectedLocation") // Deliberately exercises the throwing-equality contract.
        override fun equals(other: Any?): Boolean {
            failure?.let { throw it }
            return other is Value && id == other.id
        }

        override fun hashCode(): Int = id
    }
}
