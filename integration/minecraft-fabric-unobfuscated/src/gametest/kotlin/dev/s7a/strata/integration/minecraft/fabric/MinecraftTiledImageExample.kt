package dev.s7a.strata.integration.minecraft.fabric

// showcase-source-begin:tiled-image
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription

/**
 * Builds a deterministic tiled raster from twelve independently reusable immutable images.
 *
 * The source remains authoritative for tile state, the pan-and-zoom state owns only the viewport transform, and the marker remains a fixed logical size while following one content coordinate.
 *
 * @param navigation caller-owned transform used by the showcase and loaded cache verification.
 * @param markerPositions externally owned marker coordinates committed independently from tile revisions.
 * @return one-shot definition containing a 4 by 3 tile map and one content-position overlay.
 */
internal fun createTiledImageShowcaseScreenDefinition(
    navigation: PanZoomState = PanZoomState(),
    markerPositions: StateSource<DoubleOffset> = fixedTiledImageShowcaseMarker(),
): ScreenDefinition {
    val source = createTiledImageShowcaseSource()
    return ScreenDefinition("Tiled image showcase") {
        Stack(
            modifier = Modifier.Empty.size(112, 88).background(ArgbColor(0xFF000000.toInt())),
            contentAlignment = Alignment.Center,
        ) {
            TiledImage(
                source = source,
                state = navigation,
                size = IntSize(96, 72),
                modifier = Modifier.Empty.panZoom(navigation),
            ) {
                Spacer(
                    Modifier.Empty
                        .size(7, 7)
                        .background(ArgbColor(0xFFFFFFFF.toInt()))
                        .atContentPosition(markerPositions),
                )
            }
        }
    }
}

private fun fixedTiledImageShowcaseMarker(): StateSource<DoubleOffset> =
    StateSource {
        StateSubscription(StateSnapshot(StateRevision(0L), DoubleOffset(32.0, 24.0))) {}
    }

private fun createTiledImageShowcaseSource(): TiledImageSource {
    val tileSize = IntSize(16, 16)
    val colors =
        listOf(
            0xFF1B4965.toInt(),
            0xFF2C7DA0.toInt(),
            0xFF468FAF.toInt(),
            0xFF61A5C2.toInt(),
            0xFF2D6A4F.toInt(),
            0xFF40916C.toInt(),
            0xFF52B788.toInt(),
            0xFF74C69D.toInt(),
            0xFF7F5539.toInt(),
            0xFF9C6644.toInt(),
            0xFFB08968.toInt(),
            0xFFDDB892.toInt(),
        )
    val tiles =
        colors
            .mapIndexed { index, color ->
                val id = TiledImageTileId(level = 0, column = (index % 4).toLong(), row = (index / 4).toLong())
                val image = createDrawImage(tileSize, IntArray(tileSize.width * tileSize.height) { color })
                id to
                    StateSource<TiledImageTile> {
                        StateSubscription(
                            StateSnapshot(StateRevision(0L), TiledImageTile.Ready(image)),
                        ) {}
                    }
            }.toMap()
    return object : TiledImageSource {
        override val bounds: LongRect = LongRect(0L, 0L, 64L, 48L)
        override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(tileSize, contentUnitsPerPixel = 1L))

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> = requireNotNull(tiles[id]) { "The showcase source does not contain tile $id." }
    }
}
// showcase-source-end:tiled-image
