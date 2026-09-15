package dev.s7a.strata.runtime.web

/**
 * Browser presentation policy shared by build rendering and interactive startup.
 * Minecraft uses original CSS styling and browser monospace text; it requires no game assets or native font decoder.
 */
public enum class WebTheme(
    internal val token: String,
    internal val font: String,
) {
    Native("native", "16px sans-serif"),
    Minecraft("minecraft", "16px 'Lucida Console', monospace"),
}
