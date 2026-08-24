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
 * Emits an application-owned energy gauge by composing general Strata primitives.
 */
internal fun UiScope.EnergyGauge(
    stored: Int,
    capacity: Int,
    modifier: Modifier = Modifier.Empty,
    key: ElementKey<*>? = null,
) {
    require(0 < capacity) { "Energy capacity must be positive." }
    val fillWidth = stored.coerceIn(0, capacity) * 76 / capacity
    Column(
        modifier = modifier,
        spacing = 3,
        key = key,
    ) {
        Text("$stored / $capacity E")
        Stack(
            modifier = Modifier.Empty.size(80, 8).background(ArgbColor(0xFF1A2226.toInt())),
            contentAlignment = Alignment.CenterStart,
        ) {
            Spacer(
                modifier = Modifier.Empty.size(fillWidth, 6).background(ArgbColor(0xFF20C7DF.toInt())),
            )
        }
    }
}
// showcase-source-end:skill-custom
