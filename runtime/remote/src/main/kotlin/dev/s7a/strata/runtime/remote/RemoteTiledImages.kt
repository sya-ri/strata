@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionFields
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.projection.StandardTiledImageProjections
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Transfers bounded committed tile pixels and reconstructs the existing viewport and renderer.
 * Transform edits use the shared optimistic PanZoom binding; server source functions remain server-owned.
 */
internal class RemoteTiledImages(
    private val transform: RemotePanZoom,
) {
    private val sourceKey = RemoteStateKey(SourceOwner::class)

    /**
     * Registers the viewport and private tile-layer schemas with one registry-owned source token.
     */
    fun register(registry: RemoteRegistry) {
        registry.element(BuiltinProjection.TiledImage.type, { value ->
            val fields = ProjectionFields(value)
            val geometry = geometry(fields)
            val fit = PanZoomFit.entries[fields.int(PanZoomFit.entries.indices)]
            fields.finish()
            Viewport(geometry, fit)
        }, { properties, context -> transform.prepare(properties.geometry.transform, context) }) { properties, context ->
            val geometry = properties.geometry
            StandardTiledImageProjections.viewport(geometry, geometry.bounds, geometry.levels, transform.get(geometry.transform, context.states), geometry.size, properties.fit, context.modifier, context.key, context.children)
        }
        registry.element(BuiltinProjection.TiledImageLayer.type, ::layer, { properties, context ->
            transform.prepare(properties.geometry.transform, context)
            context.states.prepare(context.identity, sourceKey, { SourceOwner() }, { it.update(properties) }, { it.close() })
        }) { properties, context ->
            require(context.children.isEmpty())
            val source = checkNotNull(context.states.get(context.identity, sourceKey).source)
            StandardTiledImageProjections.layer(source, transform.get(properties.geometry.transform, context.states), properties.geometry.size, properties.policy)
        }
    }

    private fun geometry(fields: ProjectionFields): Geometry {
        val coordinates = ProjectionFields(fields.value())
        val bounds = LongRect(coordinates.long(), coordinates.long(), coordinates.long(), coordinates.long())
        coordinates.finish()
        val levels =
            fields.values().map { value ->
                val level = ProjectionFields(value)
                TiledImageLevel(IntSize(level.int(1..Int.MAX_VALUE), level.int(1..Int.MAX_VALUE)), level.long()).also { level.finish() }
            }
        val size = IntSize(fields.int(1..Int.MAX_VALUE), fields.int(1..Int.MAX_VALUE))
        StandardTiledImageProjections.validate(bounds, levels, size)
        return Geometry(bounds, levels, size, transform.properties(fields))
    }

    private fun layer(value: ProjectionValue): Layer {
        val fields = ProjectionFields(value)
        val geometry = geometry(fields)
        val generation = fields.long().also { require(0 < it) }
        val policy = TiledImageCachePolicy(fields.int(1..Int.MAX_VALUE), fields.long(), fields.int(0..Int.MAX_VALUE))
        val records = fields.values()
        require(records.size <= policy.maxEntries)
        var reservedBytes = 0L
        val images = RemoteImageCodec()
        val tiles =
            records.associate { record ->
                val tile = ProjectionFields(record)
                val id = TiledImageTileId(tile.int(geometry.levels.indices), tile.long(), tile.long())
                val level = geometry.levels[id.level]
                val bytes = Math.multiplyExact(Math.multiplyExact(level.tilePixelSize.width.toLong(), level.tilePixelSize.height.toLong()), 4L)
                reservedBytes = Math.addExact(reservedBytes, bytes)
                require(reservedBytes <= policy.maxBytes)
                val width = Math.multiplyExact(level.tilePixelSize.width.toLong(), level.contentUnitsPerPixel)
                val height = Math.multiplyExact(level.tilePixelSize.height.toLong(), level.contentUnitsPerPixel)
                require(id.column in Math.floorDiv(geometry.bounds.left, width)..Math.floorDiv(geometry.bounds.right - 1L, width))
                require(id.row in Math.floorDiv(geometry.bounds.top, height)..Math.floorDiv(geometry.bounds.bottom - 1L, height))
                val encoded = tile.value()
                val image =
                    if (encoded === ProjectionValue.Absent) {
                        TiledImageTile.Empty
                    } else {
                        val pixels = requireNotNull(images.decode(encoded) as? ImageSource.Pixels)
                        require(pixels.image.size == level.tilePixelSize)
                        TiledImageTile.Ready(pixels.image)
                    }
                tile.finish()
                id to image
            }
        require(tiles.size == records.size) { "Duplicate remote tile." }
        fields.finish()
        return Layer(geometry, generation, policy, tiles)
    }

    /**
     * Immutable source geometry and current optimistic transform description.
     */
    private class Geometry(
        val bounds: LongRect,
        val levels: List<TiledImageLevel>,
        val size: IntSize,
        val transform: RemotePanZoom.Properties,
    )

    /**
     * Detached viewport fit policy.
     */
    private class Viewport(
        val geometry: Geometry,
        val fit: PanZoomFit,
    )

    /**
     * Complete validated current tile working set, never offscreen history.
     */
    private class Layer(
        val geometry: Geometry,
        val generation: Long,
        val policy: TiledImageCachePolicy,
        val tiles: Map<TiledImageTileId, TiledImageTile>,
    )

    /**
     * Holds one source generation; replacement and screen retirement release its pixels and callbacks.
     */
    private class SourceOwner : AutoCloseable {
        var source: RemoteTileSource? = null

        fun update(properties: Layer) {
            val previous = source
            if (previous == null || previous.generation != properties.generation) {
                previous?.close()
                source = RemoteTileSource(properties.generation, properties.geometry.bounds, properties.geometry.levels)
            }
            val current = checkNotNull(source)
            require(current.bounds == properties.geometry.bounds && current.levels == properties.geometry.levels) { "Remote tile geometry changed within one source generation." }
            current.update(properties.tiles)
        }

        override fun close() {
            val previous = source
            source = null
            previous?.close()
        }
    }
}
