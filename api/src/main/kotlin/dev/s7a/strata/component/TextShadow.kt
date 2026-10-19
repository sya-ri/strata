package dev.s7a.strata.component

import dev.s7a.strata.render.ArgbColor

/**
 * Color policy for a text shadow at the renderer's ordinary glyph offset.
 * Set [TextStyle.shadow] to null to disable the shadow.
 */
public sealed interface TextShadow {
    /**
     * Divides the effective foreground's RGB channels by four while preserving its alpha.
     */
    public data object Automatic : TextShadow

    /**
     * Uses a fixed shadow tint independently of the foreground and editor state.
     * @property color straight ARGB shadow tint, including alpha.
     */
    public data class Color(
        public val color: ArgbColor,
    ) : TextShadow
}
