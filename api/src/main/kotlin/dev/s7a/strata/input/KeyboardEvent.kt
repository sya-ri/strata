package dev.s7a.strata.input

/**
 * Immutable typed keyboard event delivered to the currently focused retained component.
 */
public sealed interface KeyboardEvent {
    /**
     * Physical key identity.
     */
    public val key: KeyCode

    /**
     * Platform scan code preserved for layout-specific consumers.
     */
    public val scanCode: Int

    /**
     * Modifier state captured with the event.
     */
    public val modifiers: KeyboardModifiers

    /**
     * Key press or repeat delivery.
     *
     * @property key physical key identity.
     * @property scanCode platform scan code.
     * @property modifiers captured modifier state.
     */
    public data class Press(
        override val key: KeyCode,
        override val scanCode: Int,
        override val modifiers: KeyboardModifiers = KeyboardModifiers(),
    ) : KeyboardEvent

    /**
     * Key release delivery.
     *
     * @property key physical key identity.
     * @property scanCode platform scan code.
     * @property modifiers captured modifier state.
     */
    public data class Release(
        override val key: KeyCode,
        override val scanCode: Int,
        override val modifiers: KeyboardModifiers = KeyboardModifiers(),
    ) : KeyboardEvent
}
