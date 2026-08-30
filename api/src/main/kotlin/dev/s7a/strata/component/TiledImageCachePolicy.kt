package dev.s7a.strata.component

/**
 * Bounds one retained tiled-image attachment's current tile working set.
 *
 * The component reserves one entry and the level's complete RGBA8 byte cost before subscribing, even while a tile is empty.
 * The working set contains only tiles intersecting the visible rectangle plus [overscanTiles], together with visible coarser fallback tiles; it retains no offscreen history beyond that margin.
 * If the preferred level cannot fit, the component selects a coarser level before changing subscriptions.
 * If even the coarsest visible set cannot fit, layout fails before partially installing that set.
 *
 * @property maxEntries positive maximum number of simultaneous tile observations.
 * @property maxBytes positive maximum reserved straight-RGBA8 byte cost of simultaneous observations.
 * @property overscanTiles non-negative tile margin retained around the selected level's visible range; coarser fallback levels retain only visible tiles.
 * @throws IllegalArgumentException when either maximum is not positive or the margin is negative.
 */
public data class TiledImageCachePolicy(
    public val maxEntries: Int = 512,
    public val maxBytes: Long = 67_108_864L,
    public val overscanTiles: Int = 1,
) {
    init {
        require(0 < maxEntries) { "Tiled image cache entries must be positive." }
        require(0 < maxBytes) { "Tiled image cache bytes must be positive." }
        require(0 <= overscanTiles) { "Tiled image overscan must be non-negative." }
    }

    /**
     * Default bounded working-set policy for ordinary image viewers and maps.
     */
    public companion object {
        /**
         * A 512-entry, 64-MiB working set with one tile of overscan.
         */
        public val Default: TiledImageCachePolicy = TiledImageCachePolicy()
    }
}
