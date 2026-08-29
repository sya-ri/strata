package dev.s7a.strata.component

/**
 * A positive integer scale for one eight-by-eight Minecraft player-head layer.
 *
 * Each factor maps one source skin texel to that many logical pixels, so [factor] one produces an 8 by 8 head and factor three produces a 24 by 24 head without uneven texel sizes.
 *
 * @property factor positive number of logical pixels per source texel.
 * @throws IllegalArgumentException when [factor] is not positive or the resulting logical size would overflow [Int].
 */
public data class PlayerHeadScale(
    public val factor: Int,
) {
    init {
        require(0 < factor) { "PlayerHead scale factor must be positive." }
        require(factor <= Int.MAX_VALUE / SOURCE_SIZE) { "PlayerHead scaled size must fit in Int." }
    }

    /**
     * The resulting square logical extent.
     */
    public val logicalSize: Int
        get() = factor * SOURCE_SIZE

    private companion object {
        private const val SOURCE_SIZE: Int = 8
    }
}
