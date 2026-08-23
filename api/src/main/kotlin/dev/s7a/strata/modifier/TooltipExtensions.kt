@file:JvmName("TooltipModifiers")

package dev.s7a.strata.modifier

import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Shows a profile-backed root tooltip while the modified element remains hovered.
 *
 * The tooltip is painted after the complete screen, escapes ancestor child clips, and is clamped to the root viewport.
 *
 * @receiver immutable modifier chain built during an active screen evaluation.
 * @param text unresolved single-line tooltip text.
 * @param delayMillis non-negative hover delay.
 * @return a new chain containing one retained tooltip behavior.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.tooltip(
    text: UiText,
    delayMillis: Long = 500L,
): Modifier {
    require(0L <= delayMillis) { "Tooltip delay must be non-negative." }
    return ComponentRuntimeBridge.current().tooltip(this, text, delayMillis)
}

/**
 * Literal-text overload of [tooltip].
 */
public fun Modifier.tooltip(
    text: String,
    delayMillis: Long = 500L,
): Modifier = tooltip(UiText.Literal(text), delayMillis)
