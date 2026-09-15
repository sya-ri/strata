@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.CheckboxState
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateObservation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Verifies that existing component-state APIs participate in the same declarative dependency tracking on both targets.
 */
internal class StandardComponentStateObservationTest {
    @Test
    fun standardValuesInvalidateExactlyOnceAndUnsubscribeWhenNoLongerRead() {
        val checkbox = CheckboxState()
        val slider = SliderState(0.0)
        val cycle = CycleButtonState(listOf(false, true), false)
        val text = TextFieldState()
        val area = TextAreaState()
        var invalidations = 0
        val observation = StateObservation({}, {}, { invalidations += 1 }, {})
        observation.evaluate {
            listOf(checkbox.checked, slider.value, cycle.value, text.value, area.value)
        }
        checkbox.checked = true
        slider.value = 1.0
        cycle.value = true
        text.value = "Changed"
        area.value = "First\r\nSecond"
        assertEquals(5, invalidations)
        area.value = "First\nSecond"
        assertEquals(5, invalidations)
        observation.evaluate { checkbox.checked }
        text.value = "Unread"
        assertEquals(5, invalidations)
        observation.close()
        checkbox.checked = false
        assertEquals(5, invalidations)
    }

    @Test
    fun declarationCannotWriteEvenAnUnobservedComponentState() {
        val checkbox = CheckboxState()
        val text = TextFieldState("Initial")
        val observation = StateObservation({}, {}, {}, {})
        assertFailsWith<IllegalStateException> { observation.evaluate { checkbox.checked = true } }
        assertFailsWith<IllegalStateException> { observation.evaluate { text.value = "Forbidden" } }
        assertEquals(false, checkbox.checked)
        assertEquals("Initial", text.value)
        observation.close()
    }
}
