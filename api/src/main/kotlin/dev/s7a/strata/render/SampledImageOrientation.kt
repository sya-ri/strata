package dev.s7a.strata.render

/**
 * Immutable source-axis directions for nearest sampling through normalized source and destination rectangles.
 * Reversal swaps the corresponding source endpoints before interpolation and does not rearrange image pixels.
 * This preserves the selected source texel at exact nearest-neighbor boundaries and is safe to share between threads.
 *
 * @property flipX whether the source right edge maps to the destination left edge.
 * @property flipY whether the source bottom edge maps to the destination top edge.
 */
public enum class SampledImageOrientation(
    public val flipX: Boolean,
    public val flipY: Boolean,
) {
    /**
     * Source coordinates increase along both destination axes.
     */
    Normal(false, false),

    /**
     * Source x coordinates decrease while source y coordinates increase.
     */
    FlipHorizontal(true, false),

    /**
     * Source y coordinates decrease while source x coordinates increase.
     */
    FlipVertical(false, true),

    /**
     * Source coordinates decrease along both destination axes.
     */
    FlipBoth(true, true),
}
