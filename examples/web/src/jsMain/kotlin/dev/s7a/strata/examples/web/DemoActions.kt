package dev.s7a.strata.examples.web

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate

/**
 * Keeps the appearance and activation contract controlled by the same enabled state.
 */
internal fun UiScope.actionButton(
    label: String,
    enabled: Boolean = true,
    width: Int = 96,
    action: () -> Unit,
) {
    Button(label, width = width, enabled = enabled, modifier = Modifier.Empty.onActivate(enabled = enabled, action = action))
}
