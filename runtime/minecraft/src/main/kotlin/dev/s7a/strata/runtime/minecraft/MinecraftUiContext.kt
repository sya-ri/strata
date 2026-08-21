@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.dsl.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.text.UiText

/**
 * Callback-lifetime implicit receiver for Minecraft-backed component DSL functions.
 *
 * The context is confined to the thread and dynamic extent of its screen-content callback.
 * Calls from another thread or after the callback returns fail before retaining arguments.
 * Member extensions emit directly into the active [UiScope], so application code uses [MenuBackground], [Text], and [Button] without naming or retaining this context.
 */
public sealed interface MinecraftUiContext {
    /**
     * Creates a profile-backed Minecraft menu-background element.
     *
     * Measurement fills finite maximum constraints, while paint repeats the 16 by 16 profile image as nearest-sampled 32 by 32 logical tiles in row-major order and preserves overflowing edge tiles.
     * An unbounded measurement axis fails without a finite fallback.
     *
     * @param modifier active behavior applied to the background.
     * @param key optional stable identity among direct siblings.
     * The emitted description retains the immutable background asset required by its behavior.
     *
     * @throws IllegalStateException when either receiver is used from another thread or outside its callback.
     */
    public fun UiScope.MenuBackground(
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    )

    /**
     * Creates one single-line printable-ASCII text element.
     *
     * Only [UiText.Literal] values containing U+0020 through U+007E are accepted.
     * The natural height is nine logical pixels, and the natural width is the checked sum of profile glyph advances with a four-pixel space advance.
     * A later measure requires constraints containing that exact natural size; this component does not clip, wrap, shrink, or substitute unsupported text.
     *
     * @param text unresolved printable-ASCII literal retained unchanged for semantics.
     * @param modifier active behavior applied to the text.
     * @param key optional stable identity among direct siblings.
     * @throws IllegalArgumentException when the text is not a literal or contains an unsupported code point.
     * @throws ArithmeticException when checked natural-width arithmetic overflows.
     * @throws IllegalStateException when either receiver is used from another thread or outside its callback.
     */
    public fun UiScope.Text(
        text: UiText,
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    )

    /**
     * Emits one single-line printable-ASCII literal text component into this UI scope.
     *
     * This convenience overload converts [text] to [UiText.Literal] and otherwise has the same ownership, threading, sizing, and failure behavior as [Text].
     *
     * @param text printable-ASCII literal retained unchanged for semantics.
     * @param modifier active behavior applied to the text.
     * @param key optional stable identity among direct siblings.
     * @throws IllegalArgumentException when [text] contains an unsupported code point.
     * @throws ArithmeticException when checked natural-width arithmetic overflows.
     * @throws IllegalStateException when either receiver is used from another thread or outside its callback.
     */
    public fun UiScope.Text(
        text: String,
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    ) {
        Text(UiText.Literal(text), modifier, key)
    }

    /**
     * Emits one fixed-size printable-ASCII pointer button.
     *
     * The natural size is exactly 150 by 20 logical pixels and constraints must admit that size.
     * The label is centered on the fixed button and must fit within 146 logical pixels.
     * Pointer movement updates hover only when the host receives an event; a stationary pointer does not create an implicit update.
     * The component owns enabled or disabled semantics and event-driven hover visuals.
     * Reusable pointer actions are supplied through active modifiers such as `Modifier.onPress`, `Modifier.onRelease`, `Modifier.onMove`, `Modifier.onScroll`, and `Modifier.onHover`.
     *
     * @param label unresolved printable-ASCII literal retained for semantics.
     * @param enabled whether the button uses enabled semantics and hover visuals.
     * @param modifier active behavior applied to the button.
     * @param key optional stable identity among direct siblings.
     * @throws IllegalArgumentException when [label] is unsupported or wider than 146 logical pixels.
     * @throws ArithmeticException when checked label-width arithmetic overflows.
     * @throws IllegalStateException when either receiver is used from another thread or outside its callback.
     */
    public fun UiScope.Button(
        label: UiText,
        enabled: Boolean = true,
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    )

    /**
     * Emits one fixed-size printable-ASCII literal pointer button into this UI scope.
     *
     * This convenience overload converts [label] to [UiText.Literal] and otherwise has the same ownership, threading, interaction, sizing, and failure behavior as [Button].
     *
     * @param label printable-ASCII label shown by the button and exposed as button semantics.
     * @param enabled whether the button uses enabled semantics and hover visuals.
     * @param modifier active behavior applied to the button.
     * @param key optional stable identity among direct siblings.
     * @throws IllegalArgumentException when [label] contains an unsupported code point or is wider than 146 logical pixels.
     * @throws ArithmeticException when checked label-width arithmetic overflows.
     * @throws IllegalStateException when either receiver is used from another thread or outside its callback.
     */
    public fun UiScope.Button(
        label: String,
        enabled: Boolean = true,
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    ) {
        Button(UiText.Literal(label), enabled, modifier, key)
    }
}
