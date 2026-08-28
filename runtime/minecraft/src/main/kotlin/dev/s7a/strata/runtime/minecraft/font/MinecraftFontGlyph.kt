package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.SampledImageOrientation

/**
 * Immutable detached glyph pixels and native logical metrics.
 * Ink bounds are relative to the same top-left logical origin supplied to Minecraft text drawing.
 * The image contains the complete sampled glyph rectangle, ready for ordinary RGBA tint multiplication; intensity backends replicate coverage into every channel before returning it.
 * A null image represents an advance without ink unless [oversizedRasterSize] records an atlas-rejected source raster.
 * [channel] records provenance without requiring another shader conversion.
 * [orientation] preserves reversed native source coordinates through normalized ink bounds without rearranging pixels.
 * Advance and ink metrics may retain native NaN or infinity; callers must omit non-finite final quads before constructing portable geometry.
 * Finite bounds remain normalized, while offsets stay finite and non-negative.
 * Values are safe to retain after their engine and native face close.
 *
 * @property advance ordinary cursor advance, including a provider's configured spacing.
 * @property left left ink-quad offset.
 * @property top top ink-quad offset.
 * @property right right ink-quad offset.
 * @property bottom bottom ink-quad offset.
 * @property image immutable source pixels, or null for an empty or atlas-rejected source raster.
 * @property channel source-channel interpretation.
 * @property boldOffset extra advance and offset used by bold rendering.
 * @property shadowOffset horizontal and vertical shadow offset.
 * @property orientation source-axis directions for normalized ink bounds.
 * @property oversizedRasterSize positive measured source dimensions rejected by the native 256-pixel atlas, without allocating their pixels.
 * @throws IllegalArgumentException when finite ink bounds are reversed, offsets are invalid, an image is empty, or an oversized-raster marker is inconsistent.
 */
public data class MinecraftFontGlyph(
    public val advance: Float,
    public val left: Float,
    public val top: Float,
    public val right: Float,
    public val bottom: Float,
    public val image: DrawImage?,
    public val channel: MinecraftGlyphChannel = MinecraftGlyphChannel.Color,
    public val boldOffset: Float = 1.0f,
    public val shadowOffset: Float = 1.0f,
    public val orientation: SampledImageOrientation,
    public val oversizedRasterSize: IntSize? = null,
) {
    /**
     * Creates a glyph with explicit source orientation and no deferred atlas rejection.
     * Inputs, detached ownership, and validation follow the primary constructor.
     */
    public constructor(
        advance: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        image: DrawImage?,
        channel: MinecraftGlyphChannel = MinecraftGlyphChannel.Color,
        boldOffset: Float = 1.0f,
        shadowOffset: Float = 1.0f,
        orientation: SampledImageOrientation,
    ) : this(advance, left, top, right, bottom, image, channel, boldOffset, shadowOffset, orientation, null)

    /**
     * Creates a glyph whose source coordinates increase along both normalized ink axes.
     * Inputs, detached ownership, and validation follow the primary constructor.
     */
    public constructor(
        advance: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        image: DrawImage?,
        channel: MinecraftGlyphChannel = MinecraftGlyphChannel.Color,
        boldOffset: Float = 1.0f,
        shadowOffset: Float = 1.0f,
    ) : this(advance, left, top, right, bottom, image, channel, boldOffset, shadowOffset, SampledImageOrientation.Normal, null)

    init {
        require(left.isFinite().not() || right.isFinite().not() || left <= right) { "Finite horizontal glyph bounds must not be reversed." }
        require(top.isFinite().not() || bottom.isFinite().not() || top <= bottom) { "Finite vertical glyph bounds must not be reversed." }
        require(boldOffset.isFinite() && shadowOffset.isFinite() && 0.0f <= boldOffset && 0.0f <= shadowOffset) {
            "Glyph offsets must be finite and non-negative."
        }
        if (image != null) {
            require(0 < image.size.width && 0 < image.size.height) { "Glyph images must be nonempty." }
        }
        if (oversizedRasterSize != null) {
            require(image == null && 0 < oversizedRasterSize.width && 0 < oversizedRasterSize.height) {
                "An oversized glyph must have positive measured dimensions and no allocated image."
            }
            require(256 < oversizedRasterSize.width || 256 < oversizedRasterSize.height) {
                "An oversized glyph must exceed the native atlas in at least one dimension."
            }
        }
    }
}
