package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.render.DrawImage

/**
 * Immutable nine-layer representation of one printable-ASCII Minecraft glyph.
 *
 * @property advance logical cursor advance derived from the source bitmap.
 * @property normalShadow opaque dark-gray shadow layer with transparent-white background.
 * @property normalForeground opaque white foreground layer with transparent-white background.
 * @property inactiveShadow opaque inactive dark-gray shadow layer with transparent-white background.
 * @property inactiveForeground opaque inactive gray foreground layer with transparent-white background.
 * @property textFieldShadow exact enabled EditBox shadow layer.
 * @property textFieldForeground exact enabled EditBox foreground layer.
 * @property textFieldDisabledShadow exact disabled EditBox shadow layer.
 * @property textFieldDisabledForeground exact disabled EditBox foreground layer.
 * @property containerForeground exact shadow-free container-label foreground layer.
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
    @get:JvmSynthetic
    internal val textFieldShadow: DrawImage,
    @get:JvmSynthetic
    internal val textFieldForeground: DrawImage,
    @get:JvmSynthetic
    internal val textFieldDisabledShadow: DrawImage,
    @get:JvmSynthetic
    internal val textFieldDisabledForeground: DrawImage,
    @get:JvmSynthetic
    internal val containerForeground: DrawImage,
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
         * @param textFieldShadow enabled EditBox shadow pixels.
         * @param textFieldForeground enabled EditBox foreground pixels.
         * @param textFieldDisabledShadow disabled EditBox shadow pixels.
         * @param textFieldDisabledForeground disabled EditBox foreground pixels.
         * @param containerForeground shadow-free container-label foreground pixels.
         * @return a nine-layer glyph snapshot.
         */
        @JvmSynthetic
        internal fun create(
            advance: Int,
            normalShadow: DrawImage,
            normalForeground: DrawImage,
            inactiveShadow: DrawImage,
            inactiveForeground: DrawImage,
            textFieldShadow: DrawImage,
            textFieldForeground: DrawImage,
            textFieldDisabledShadow: DrawImage,
            textFieldDisabledForeground: DrawImage,
            containerForeground: DrawImage,
        ): MinecraftGlyphSnapshot =
            MinecraftGlyphSnapshot(
                advance,
                normalShadow,
                normalForeground,
                inactiveShadow,
                inactiveForeground,
                textFieldShadow,
                textFieldForeground,
                textFieldDisabledShadow,
                textFieldDisabledForeground,
                containerForeground,
            )
    }
}
