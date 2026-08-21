package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.render.DrawImage

/**
 * Immutable four-layer representation of one printable-ASCII Minecraft glyph.
 *
 * @property advance logical cursor advance derived from the source bitmap.
 * @property normalShadow opaque dark-gray shadow layer with transparent-white background.
 * @property normalForeground opaque white foreground layer with transparent-white background.
 * @property inactiveShadow opaque inactive dark-gray shadow layer with transparent-white background.
 * @property inactiveForeground opaque inactive gray foreground layer with transparent-white background.
 */
internal class MinecraftGlyphSnapshot private constructor(
    @get:JvmSynthetic
    internal val advance: Int,
    @get:JvmSynthetic
    internal val normalShadow: DrawImage,
    @get:JvmSynthetic
    internal val normalForeground: DrawImage,
    @get:JvmSynthetic
    internal val inactiveShadow: DrawImage,
    @get:JvmSynthetic
    internal val inactiveForeground: DrawImage,
) {
    /**
     * Owns the synthetic constructor bridge for immutable glyph snapshots.
     */
    companion object {
        /**
         * Creates one validated immutable glyph snapshot without exposing its constructor to Java.
         *
         * @param advance positive logical cursor advance.
         * @param normalShadow normal shadow pixels.
         * @param normalForeground normal foreground pixels.
         * @param inactiveShadow inactive shadow pixels.
         * @param inactiveForeground inactive foreground pixels.
         * @return a four-layer glyph snapshot.
         */
        @JvmSynthetic
        internal fun create(
            advance: Int,
            normalShadow: DrawImage,
            normalForeground: DrawImage,
            inactiveShadow: DrawImage,
            inactiveForeground: DrawImage,
        ): MinecraftGlyphSnapshot =
            MinecraftGlyphSnapshot(
                advance,
                normalShadow,
                normalForeground,
                inactiveShadow,
                inactiveForeground,
            )
    }
}
