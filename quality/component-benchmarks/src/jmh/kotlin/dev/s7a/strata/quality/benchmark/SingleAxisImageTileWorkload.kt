package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize

/**
 * Complete singleton-axis background corpus and unchanged controls, shared by every measured boundary.
 * [viewport] is the host constraint; [design] is the local producer extent before an optional fractional fit.
 * Source sizes and modes are fixed inputs, and [mixed] adds ordinary content after the background.
 */
public enum class SingleAxisImageTileWorkload(
    public val source: IntSize,
    public val viewport: IntSize,
    public val mapping: Mapping = Mapping.Tile,
    public val design: IntSize = viewport,
    public val mixed: Boolean = false,
) {
    SinglePixelLarge(IntSize(1, 1), IntSize(320, 180)),
    WidthOneLarge(IntSize(1, 8), IntSize(320, 180)),
    HeightOneLarge(IntSize(8, 1), IntSize(320, 180)),
    SinglePixelWide(IntSize(1, 1), IntSize(641, 367)),
    WidthOneLong(IntSize(1, 257), IntSize(641, 367)),
    HeightOneLong(IntSize(257, 1), IntSize(641, 367)),
    SinglePixelSmall(IntSize(1, 1), IntSize(7, 5)),
    WidthOneSmall(IntSize(1, 8), IntSize(7, 5)),
    HeightOneSmall(IntSize(8, 1), IntSize(7, 5)),
    ZeroArea(IntSize(1, 1), IntSize.Zero),
    MultiPattern(IntSize(2, 3), IntSize(320, 180)),
    EqualMulti(IntSize(2, 2), IntSize(320, 180)),
    Stretch(IntSize(2, 3), IntSize(320, 180), Mapping.Stretch),
    NineSlice(IntSize(3, 3), IntSize(320, 180), Mapping.NineSlice),
    FractionalOriginal(IntSize(1, 8), IntSize(320, 180), Mapping.Fractional, IntSize(257, 149)),
    MixedContent(IntSize(1, 8), IntSize(320, 180), mixed = true),
    ;

    /**
     * Typed public image mapping or the unchanged NineSlice control.
     * Fractional uses ordinary tiling inside a continuously fitted local design.
     */
    public enum class Mapping { Tile, Stretch, NineSlice, Fractional }
}
