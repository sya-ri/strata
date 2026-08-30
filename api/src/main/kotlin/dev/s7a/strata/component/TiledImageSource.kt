package dev.s7a.strata.component

import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.state.StateSource

/**
 * Externally owned immutable description of one logical raster assembled from revisioned tiles.
 *
 * Source identity defines one complete generation: [bounds], [levels], tile grid geometry, and the meaning of every tile revision remain unchanged while the same instance is used.
 * Applications replace the source instance to publish another generation.
 * A retained component calls [tile] on its owner thread only for its bounded visible and overscan working set, independently subscribes to each returned source, and closes those observations without closing this source.
 * Tile callbacks may run on any thread under the ordinary [StateSource] contract and must never expose mutable image storage.
 * Every edge-tile envelope implied by [bounds] and [levels] must remain representable in the [Long] coordinate space.
 */
public interface TiledImageSource {
    /**
     * Nonempty half-open content-coordinate bounds presented by this generation, with every edge and mathematical axis midpoint exactly representable as a [Double].
     */
    public val bounds: LongRect

    /**
     * Immutable nonempty resolution levels ordered strictly from finest to coarsest.
     */
    public val levels: List<TiledImageLevel>

    /**
     * Returns the revision history for one tile owned by this source generation.
     *
     * Repeated requests for the same identifier must observe one coherent increasing revision history.
     * Ready images must exactly match the level's declared pixel size and edge pixels outside [bounds] must be transparent.
     * The method must not block on network, disk, decoding, or rendering work; it may start or reference asynchronous application work through the returned source.
     *
     * @param id tile identifier whose level is contained in [levels].
     * @return externally owned revisioned presentation state.
     * @throws IllegalArgumentException when [id] is not supported by this generation.
     * @throws Throwable when the source cannot establish the requested state source before subscription.
     */
    public fun tile(id: TiledImageTileId): StateSource<TiledImageTile>
}
