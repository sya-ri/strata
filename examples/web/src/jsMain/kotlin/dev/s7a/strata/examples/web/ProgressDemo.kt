package dev.s7a.strata.examples.web

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.width
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition

/**
 * Creates determinate progress with integer steps so both endpoints are exact.
 */
internal fun progressDemo(): UiDefinition {
    val steps = mutableStateOf(0)
    return UiDefinition("Progress") {
        Column(spacing = 16) {
            Text("Progress: ${steps.value * 10}%", modifier = Modifier.Empty.width(304))
            ProgressBar(steps.value / 10.0, IntSize(304, 24))
            Row(spacing = 8) {
                actionButton("Decrease", enabled = 0 < steps.value) { steps.value -= 1 }
                actionButton("Increase", enabled = steps.value < 10) { steps.value += 1 }
                actionButton("Reset") { steps.value = 0 }
            }
        }
    }
}
