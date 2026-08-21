package dev.s7a.strata.input

/**
 * Typed retained focus transition delivered only when focused ownership changes.
 */
public enum class FocusEvent {
    /**
     * The component became the keyboard and text-input target.
     */
    Gained,

    /**
     * The component stopped being the keyboard and text-input target.
     */
    Lost,
}
