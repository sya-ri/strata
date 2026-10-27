@file:JvmName("TooltipModifiers")

package dev.s7a.strata.modifier

import dev.s7a.strata.component.UiScope
import dev.s7a.strata.component.buildComponentTree
import dev.s7a.strata.spi.ComponentRuntimeBridge
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import kotlin.jvm.JvmName

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

/**
 * Shows text with optional standard tooltip decoration and content inset.
 * Disabling [decorated] displays only the text, without the background, frame, or inset.
 * See [tooltip] for hover timing and root placement.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.tooltip(
    text: UiText,
    decorated: Boolean,
    delayMillis: Long = 500L,
): Modifier {
    require(0L <= delayMillis) { "Tooltip delay must be non-negative." }
    return ComponentRuntimeBridge.current().tooltip(this, text, decorated, delayMillis)
}

/**
 * Literal-text overload with optional standard tooltip decoration.
 */
public fun Modifier.tooltip(
    text: String,
    decorated: Boolean,
    delayMillis: Long = 500L,
): Modifier = tooltip(UiText.Literal(text), decorated, delayMillis)

/**
 * Shows one display-only component root after the hover delay.
 * Content is evaluated immediately and must emit exactly one root; use a layout to group multiple components.
 * The root escapes ancestor clips, is constrained to the viewport, and does not receive input or focus.
 * Decoration adds the runtime's standard background, frame, and three-pixel inset.
 * Disabling [decorated] displays only the content; the four-pixel anchor gap remains.
 * Components remain subject to the active runtime's supported capabilities.
 *
 * @param delayMillis non-negative hover delay, defaulting to 500 milliseconds.
 * @param decorated whether to apply the standard tooltip surface and content inset, defaulting to true.
 * @param content synchronous callback producing the tooltip root.
 */
@OptIn(InternalStrataRuntimeApi::class)
public fun Modifier.tooltip(
    delayMillis: Long = 500L,
    decorated: Boolean = true,
    content: UiScope.() -> Unit,
): Modifier {
    require(0L <= delayMillis) { "Tooltip delay must be non-negative." }
    val root = buildComponentTree(content)
    return ComponentRuntimeBridge.current().tooltip(this, root, decorated, delayMillis)
}
