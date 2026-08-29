package dev.s7a.strata.integration.consumer

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.modifier.size
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Creates an API-only tiled image backed by one independently revisioned tile.
 *
 * The returned definition captures one source generation and [navigation] for its lifetime.
 * The caller owns [frames] and [navigation], opens the definition on the navigation state's owner thread, and supplies ready images whose pixel size equals [size].
 * The centered spacer demonstrates fixed-size content-coordinate overlay placement without joining or copying tile pixels.
 *
 * @param frames externally owned revisions for the sole tile at level zero, column zero, and row zero.
 * @param size positive tile, content, and logical viewport extent.
 * @param navigation caller-owned pan-and-zoom transform confined to its constructing thread.
 * @return an unevaluated one-shot screen definition, owned by the caller until opened or closed.
 * @throws IllegalArgumentException when [size] is not positive.
 */
public fun createApiOnlyTiledImageDefinition(
    frames: StateSource<TiledImageTile>,
    size: IntSize,
    navigation: PanZoomState = PanZoomState(),
): ScreenDefinition {
    val level = TiledImageLevel(size, contentUnitsPerPixel = 1L)
    val source =
        object : TiledImageSource {
            override val bounds: LongRect = LongRect(0L, 0L, size.width.toLong(), size.height.toLong())
            override val levels: List<TiledImageLevel> = listOf(level)

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                require(id == TiledImageTileId(level = 0, column = 0L, row = 0L)) { "The API-only tiled image contains one tile." }
                return frames
            }
        }
    val contentCenter = DoubleOffset(size.width / 2.0, size.height / 2.0)
    val markerPositions =
        StateSource {
            StateSubscription(StateSnapshot(StateRevision(0L), contentCenter)) {}
        }
    return ScreenDefinition("API-only TiledImage") {
        TiledImage(
            source = source,
            state = navigation,
            size = size,
            modifier = Modifier.Empty.panZoom(navigation),
        ) {
            Spacer(
                modifier =
                    Modifier.Empty
                        .size(1, 1)
                        .atContentPosition(markerPositions),
            )
        }
    }
}
