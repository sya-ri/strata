package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.text.UiText

/**
 * Callback-lifetime factory context for Minecraft-backed element descriptions.
 *
 * The context is confined to the thread and dynamic extent of its screen-content callback.
 * Calls from another thread or after the callback returns fail before retaining arguments.
 * Returned elements retain only the immutable assets required by their behavior, plus the value, callback, and host-coordinator ownership needed by that behavior.
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
     * @return a platform-neutral element backed by the transferred Minecraft profile.
     * @throws IllegalStateException when the context is used from another thread or outside its callback.
     */
    public fun menuBackground(
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    ): Element

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
     * @return a platform-neutral element backed by immutable glyph layers.
     * @throws IllegalArgumentException when the text is not a literal or contains an unsupported code point.
     * @throws ArithmeticException when checked natural-width arithmetic overflows.
     * @throws IllegalStateException when the context is used from another thread or outside its callback.
     */
    public fun text(
        text: UiText,
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
    ): Element

    /**
     * Creates one fixed-size printable-ASCII pointer button.
     *
     * The natural size is exactly 150 by 20 logical pixels and constraints must admit that size.
     * The label is centered on the fixed button and must fit within 146 logical pixels.
     * Pointer movement updates hover only when the host receives an event; a stationary pointer does not create an implicit update.
     * Primary presses invoke [onPress] synchronously when enabled and otherwise remain available to lower hit targets.
     * The retained engine description and live node keep the [onPress] capture graph until reconciliation replaces them or terminal disposal releases the node.
     * An old description retained separately by application code remains caller-owned and continues retaining its callback.
     *
     * @param label unresolved printable-ASCII literal retained for semantics.
     * @param enabled whether the button can hover and consume a primary press.
     * @param modifier active behavior applied to the button.
     * @param key optional stable identity among direct siblings.
     * @param onPress callback invoked once for each consumed primary press.
     * @return a platform-neutral fixed-size button element.
     * @throws IllegalArgumentException when [label] is unsupported or wider than 146 logical pixels.
     * @throws ArithmeticException when checked label-width arithmetic overflows.
     * @throws IllegalStateException when the context is used from another thread or outside its callback.
     * @throws Throwable when [onPress] fails during pointer dispatch; the exact failure remains primary after host cleanup.
     */
    public fun pointerButton(
        label: UiText,
        enabled: Boolean = true,
        modifier: Modifier = Modifier.Empty,
        key: ElementKey<*>? = null,
        onPress: () -> Unit,
    ): Element
}
