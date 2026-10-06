package dev.s7a.strata.integration.consumer

import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size

/**
 * Compiles conditional empty values and a consumer-defined extension against the API alone.
 */
public fun createApiOnlyModifier(enabled: Boolean): Modifier = if (enabled) Modifier.panelPadding().size(100, 40) else Modifier

private fun Modifier.panelPadding(): Modifier = padding(3)
