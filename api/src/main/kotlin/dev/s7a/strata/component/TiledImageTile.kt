package dev.s7a.strata.component

import dev.s7a.strata.render.DrawImage

/**
 * Immutable current presentation state of one tiled-image cell.
 *
 * Loading, absent, and failed application states all present as [Empty]; source-specific retry and error reporting remain outside the component.
 * A newer source revision may replace either variant.
 */
public sealed interface TiledImageTile {
    /**
     * Presents no pixels for this tile revision and allows the component to use a ready coarser fallback.
     */
    public data object Empty : TiledImageTile

    /**
     * Presents one immutable whole-tile image.
     *
     * @property image detached immutable pixels whose size must exactly match the requested level.
     */
    public data class Ready(
        public val image: DrawImage,
    ) : TiledImageTile
}
