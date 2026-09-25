package dev.s7a.strata.ui

/**
 * What happens to a HUD when another ordinary screen is present.
 */
public enum class UiVisibility {
    KeepVisible,

    /**
     * Suspend drawing and input, retaining contents and incoming remote updates.
     */
    Hide,

    /**
     * End the session permanently.
     */
    Close,
}
