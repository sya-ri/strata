package dev.s7a.strata.ui

/**
 * Native placement of a UI, independent of its retained contents.
 */
public enum class UiPresentation {
    /**
     * The foreground screen, with ordinary screen navigation.
     */
    Screen,

    /**
     * An overlay above the game HUD and below ordinary screens.
     */
    Hud,
}
