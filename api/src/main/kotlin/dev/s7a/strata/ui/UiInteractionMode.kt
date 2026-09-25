package dev.s7a.strata.ui

/**
 * Which input surface a session requests; only one HUD may own interaction.
 */
public enum class UiInteractionMode {
    /**
     * Passive HUD; ordinary game input remains untouched.
     */
    None,

    /**
     * Visible cursor and UI event delivery.
     */
    Cursor,

    /**
     * Captured cursor and camera control subject to the input policy.
     */
    Look,
}
