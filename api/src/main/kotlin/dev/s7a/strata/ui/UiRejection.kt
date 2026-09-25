package dev.s7a.strata.ui

/**
 * A rejected control request leaves the previously applied state intact.
 */
public enum class UiRejection {
    Closed,
    Unsupported,
    ScreenOpen,
    Hidden,
    Capacity,
}
