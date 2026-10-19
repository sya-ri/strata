@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.integration.docs.skill

// showcase-source-begin:skill-custom
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor

/**
 * Owns shared panel structure while its caller chooses the application content.
 */
internal fun UiScope.Panel(
    title: String,
    modifier: Modifier = Modifier,
    key: ElementKey<*>? = null,
    content: UiScope.() -> Unit,
) {
    Column(modifier = modifier, spacing = 3, key = key) {
        Text(title)
        content()
    }
}

/**
 * Composes one application into the shared panel without adding application dispatch to it.
 */
internal fun UiScope.PowerScreen(
    stored: Int,
    capacity: Int,
) {
    Panel("Power") {
        EnergyGauge(stored, capacity)
    }
}

/**
 * Reuses the same panel for independent application content.
 */
internal fun UiScope.ConnectionScreen(connected: Boolean) {
    Panel("Connection") {
        Text(if (connected) "Connected" else "Disconnected")
    }
}

/**
 * Emits an application-owned energy gauge by composing general Strata primitives.
 */
internal fun UiScope.EnergyGauge(
    stored: Int,
    capacity: Int,
    modifier: Modifier = Modifier,
    key: ElementKey<*>? = null,
) {
    require(0 < capacity) { "Energy capacity must be positive." }
    val fillWidth = (stored.coerceIn(0, capacity).toLong() * 76 / capacity).toInt()
    Column(
        modifier = modifier,
        spacing = 3,
        key = key,
    ) {
        Text("$stored / $capacity E")
        Stack(
            modifier = Modifier.size(80, 8).background(ArgbColor(0xFF1A2226.toInt())),
            contentAlignment = Alignment.CenterStart,
        ) {
            Spacer(
                modifier = Modifier.size(fillWidth, 6).background(ArgbColor(0xFF20C7DF.toInt())),
            )
        }
    }
}
// showcase-source-end:skill-custom
