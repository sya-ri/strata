package dev.s7a.strata.runtime.minecraft

/**
 * Typed mapping from one immutable source image to a component background.
 */
public enum class MinecraftImageScale {
    /**
     * Maps the complete source image to the complete destination bounds with nearest sampling.
     */
    Stretch,

    /**
     * Repeats source-sized tiles from the destination's top-left corner and clips only at final bounds.
     */
    Tile,
}
