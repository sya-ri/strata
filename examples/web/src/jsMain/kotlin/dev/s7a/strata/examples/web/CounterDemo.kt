package dev.s7a.strata.examples.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.width
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf

/**
 * Creates an independent bounded counter; actions own all state changes outside declaration evaluation.
 */
internal fun counterDemo(): ScreenDefinition {
    val count = mutableStateOf(0)
    return ScreenDefinition("Counter") {
        Column(spacing = 16) {
            Text("Count: ${count.value}", modifier = Modifier.Empty.width(304))
            Row(spacing = 8) {
                actionButton("Decrease", enabled = 0 < count.value) { count.value -= 1 }
                actionButton("Increase", enabled = count.value < 10) { count.value += 1 }
                actionButton("Reset") { count.value = 0 }
            }
            Text("Range: 0 to 10", modifier = Modifier.Empty.width(304))
        }
    }
}
