@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageScope
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies tiled-image value validation, source geometry admission, and callback-lifetime overlay scope behavior.
 */
internal class TiledImageContractTest {
    @Test
    fun tileLevelIdentifierAndCachePolicyRejectInvalidValues() {
        assertThrows(IllegalArgumentException::class.java) { TiledImageLevel(IntSize.Zero, 1L) }
        assertThrows(IllegalArgumentException::class.java) { TiledImageLevel(IntSize(1, 1), 0L) }
        assertThrows(ArithmeticException::class.java) { TiledImageLevel(IntSize(Int.MAX_VALUE, 1), Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { TiledImageTileId(-1, 0L, 0L) }
        assertThrows(IllegalArgumentException::class.java) { TiledImageCachePolicy(maxEntries = 0) }
        assertThrows(IllegalArgumentException::class.java) { TiledImageCachePolicy(maxBytes = 0L) }
        assertThrows(IllegalArgumentException::class.java) { TiledImageCachePolicy(overscanTiles = -1) }
        assertEquals(TiledImageCachePolicy(), TiledImageCachePolicy.Default)
    }

    @Test
    fun declarationRequiresPositiveImmutableAlignedSourceGeometry() {
        assertThrows(IllegalArgumentException::class.java) {
            evaluateComponentTree {
                TiledImage(source(LongRect.Zero, listOf(level())), PanZoomState(), IntSize(8, 8))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            evaluateComponentTree {
                TiledImage(source(LongRect(0L, 0L, 8L, 8L), emptyList()), PanZoomState(), IntSize(8, 8))
            }
        }
        val misaligned = listOf(level(), TiledImageLevel(IntSize(6, 6), 2L))
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                evaluateComponentTree {
                    TiledImage(source(LongRect(0L, 0L, 24L, 24L), misaligned), PanZoomState(), IntSize(8, 8))
                }
            }
        assertEquals("Coarser tiled image widths must be aligned multiples of finer widths.", failure.message)
        assertThrows(IllegalArgumentException::class.java) {
            evaluateComponentTree {
                TiledImage(source(LongRect(0L, 0L, 8L, 8L), listOf(level())), PanZoomState(), IntSize.Zero)
            }
        }
        val firstInexactInteger = 9_007_199_254_740_993L
        val precisionFailure =
            assertThrows(IllegalArgumentException::class.java) {
                evaluateComponentTree {
                    TiledImage(
                        source(LongRect(firstInexactInteger, 0L, Math.addExact(firstInexactInteger, 4L), 1L), listOf(TiledImageLevel(IntSize(1, 1), 1L))),
                        PanZoomState(),
                        IntSize(4, 1),
                    )
                }
            }
        assertEquals("Tiled image bound edges must be exactly representable in the double coordinate space.", precisionFailure.message)
    }

    @Test
    fun overlayPlacementModifierRejectsAnEscapedScope() {
        lateinit var escaped: TiledImageScope
        evaluateComponentTree {
            TiledImage(source(LongRect(0L, 0L, 8L, 8L), listOf(level())), PanZoomState(), IntSize(8, 8)) {
                escaped = this
            }
        }

        assertThrows(IllegalStateException::class.java) {
            with(escaped) {
                Modifier.Empty.atContentPosition(DoubleOffset.Zero)
            }
        }
    }

    private fun level(): TiledImageLevel = TiledImageLevel(IntSize(8, 8), 1L)

    private fun source(
        bounds: LongRect,
        levels: List<TiledImageLevel>,
    ): TiledImageSource =
        object : TiledImageSource {
            override val bounds: LongRect = bounds
            override val levels: List<TiledImageLevel> = levels

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
                StateSource {
                    StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Empty)) {}
                }
        }
}
