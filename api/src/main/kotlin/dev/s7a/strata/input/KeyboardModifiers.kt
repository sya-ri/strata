package dev.s7a.strata.input

/**
 * Immutable typed keyboard modifier state captured with one key event.
 *
 * @property shift whether either Shift modifier is active.
 * @property control whether either Control modifier is active.
 * @property alt whether either Alt modifier is active.
 * @property superKey whether the platform command or Super modifier is active.
 * @property capsLock whether Caps Lock is active when the platform reports lock state.
 * @property numLock whether Num Lock is active when the platform reports lock state.
 */
public data class KeyboardModifiers(
    public val shift: Boolean = false,
    public val control: Boolean = false,
    public val alt: Boolean = false,
    public val superKey: Boolean = false,
    public val capsLock: Boolean = false,
    public val numLock: Boolean = false,
)
