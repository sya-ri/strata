@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies large logical ranges without allocating their full contents on JVM and JavaScript.
 */
internal class PortableExtentTest {
    @Test
    fun virtualListCanReachTheLastItemWithoutWrappingOverscan() {
        val state = VirtualListState<Int>()
        val created = mutableListOf<Int>()
        val tree = UiTree()
        try {
            tree.update(
                evaluateComponentTree {
                    VirtualList(
                        itemCount = Int.MAX_VALUE,
                        itemAt = { it },
                        keyAt = { it },
                        state = state,
                        viewportSize = IntSize(1, 1),
                        rowHeight = 1,
                    ) { item ->
                        created += item
                        Spacer()
                    }
                },
            )
            tree.measure(Constraints.fixed(1, 1))
            tree.layout()
            assertTrue(state.jumpToIndex(Int.MAX_VALUE - 1))
            created.clear()
            tree.measure(Constraints.fixed(1, 1))
            tree.layout()
            assertEquals(listOf(Int.MAX_VALUE - 2, Int.MAX_VALUE - 1), created)
        } finally {
            tree.close()
        }
    }

    @Test
    fun oversizedTileCountsAndByteCostsFailBeforeSubscribing() {
        assertRejectedTiles(LongRect(0L, 0L, 1L shl 32, 1L shl 32), TiledImageLevel(IntSize(1, 1), 1L))
        assertRejectedTiles(LongRect(0L, 0L, 1L, 1L), TiledImageLevel(IntSize(Int.MAX_VALUE, Int.MAX_VALUE), 1L))
    }

    private fun assertRejectedTiles(
        contentBounds: LongRect,
        level: TiledImageLevel,
    ) {
        var requested = false
        val source =
            object : TiledImageSource {
                override val bounds = contentBounds
                override val levels = listOf(level)

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                    requested = true
                    error("An oversized working set must fail before subscribing.")
                }
            }
        val tree = UiTree()
        try {
            tree.update(
                evaluateComponentTree {
                    TiledImage(
                        source,
                        PanZoomState(),
                        IntSize(1, 1),
                        cachePolicy = TiledImageCachePolicy(maxEntries = Int.MAX_VALUE, maxBytes = Long.MAX_VALUE, overscanTiles = 0),
                    )
                },
            )
            assertFailsWith<IllegalStateException> {
                tree.measure(Constraints.fixed(1, 1))
                tree.layout()
            }
            assertFalse(requested)
        } finally {
            tree.close()
        }
    }
}
