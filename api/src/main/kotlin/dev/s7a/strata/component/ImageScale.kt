package dev.s7a.strata.component

/**
 * Typed nearest-sampled mapping from one immutable image to destination bounds.
 */
public enum class ImageScale {
    /**
     * Maps the complete source image to the complete destination bounds.
     */
    Stretch,

    /**
     * Repeats source-sized tiles from the destination top-left and clips at final bounds.
     */
    Tile,
}
