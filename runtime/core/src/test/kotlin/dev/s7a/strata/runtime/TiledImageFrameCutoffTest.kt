@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Verifies that every tile observation owned by one retained layer shares a single frame cutoff.
 */
internal class TiledImageFrameCutoffTest {
    @Test
    @Suppress("LongMethod") // A blocked second slot deterministically distinguishes one node-wide cutoff from per-entry cutoffs.
    fun sharedTileCutoffCannotMixValuesAroundTheFrameBoundary() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val firstId = TiledImageTileId(0, 0L, 0L)
        val secondId = TiledImageTileId(0, 1L, 0L)
        val initialFirst = image(0xFF010101.toInt())
        val initialSecond = image(0xFF020202.toInt())
        val updatedFirst = image(0xFF030303.toInt())
        val updatedSecond = image(0xFF040404.toInt())
        val source = TwoTileSource(level, firstId, initialFirst, initialSecond)
        val executor = Executors.newSingleThreadExecutor()
        var openedSession: UiSession? = null
        try {
            val setup =
                executor
                    .submit<Pair<UiSession, Thread>> {
                        val opened = session(source)
                        opened.attach()
                        opened.frame(CONSTRAINTS)
                        opened to Thread.currentThread()
                    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            openedSession = setup.first
            val secondGate = source.history(secondId).observerGate()
            val frameStarted = CountDownLatch(1)
            val frameFuture =
                synchronized(secondGate) {
                    val future =
                        executor.submit<UiFrame> {
                            frameStarted.countDown()
                            setup.first.frame(CONSTRAINTS, FrameTime(1L))
                        }
                    check(frameStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "The cutoff frame did not start." }
                    waitUntilBlocked(setup.second)
                    source.history(firstId).publish(TiledImageTile.Ready(updatedFirst))
                    source.history(secondId).publish(TiledImageTile.Ready(updatedSecond))
                    future
                }

            val rendered = frameFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).drawCommands.filterIsInstance<DrawCommand.SampledImage>()

            assertSame(updatedFirst, rendered[0].image)
            assertSame(updatedSecond, rendered[1].image)
        } finally {
            openedSession?.let { opened -> executor.submit { opened.close() }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            executor.shutdownNow()
            executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun session(source: TiledImageSource): UiSession =
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                TiledImage(
                    source,
                    PanZoomState(),
                    SIZE,
                    fit = PanZoomFit.Contain,
                    cachePolicy = TiledImageCachePolicy(maxEntries = 2, maxBytes = 512L, overscanTiles = 0),
                )
            }
        }

    private fun image(color: Int): DrawImage = createDrawImage(TILE_SIZE, IntArray(64) { color })

    private fun waitUntilBlocked(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (thread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.onSpinWait()
        check(thread.state == Thread.State.BLOCKED) { "The cutoff frame did not block on the selected tile slot." }
    }

    private class TwoTileSource(
        level: TiledImageLevel,
        private val firstId: TiledImageTileId,
        private val initialFirst: DrawImage,
        private val initialSecond: DrawImage,
    ) : TiledImageSource {
        override val bounds: LongRect = LongRect(0L, 0L, 16L, 8L)
        override val levels: List<TiledImageLevel> = listOf(level)
        private val histories: MutableMap<TiledImageTileId, TileHistory> = LinkedHashMap()

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
            histories.getOrPut(id) {
                val image = if (id == firstId) initialFirst else initialSecond
                TileHistory(TiledImageTile.Ready(image))
            }

        fun history(id: TiledImageTileId): TileHistory = checkNotNull(histories[id])
    }

    private class TileHistory(
        initial: TiledImageTile,
    ) : StateSource<TiledImageTile> {
        private val monitor: Any = Any()
        private var snapshot: StateSnapshot<TiledImageTile> = StateSnapshot(StateRevision(0L), initial)
        private var observer: ((StateSnapshot<TiledImageTile>) -> Unit)? = null

        override fun subscribe(observer: (StateSnapshot<TiledImageTile>) -> Unit): StateSubscription<TiledImageTile> {
            val initialSnapshot =
                synchronized(monitor) {
                    check(this.observer == null) { "A test tile supports one subscription." }
                    this.observer = observer
                    snapshot
                }
            return StateSubscription(initialSnapshot) {
                synchronized(monitor) {
                    if (this.observer === observer) this.observer = null
                }
            }
        }

        fun publish(tile: TiledImageTile) {
            val notification =
                synchronized(monitor) {
                    snapshot = StateSnapshot(StateRevision(Math.incrementExact(snapshot.revision.value)), tile)
                    snapshot to checkNotNull(observer)
                }
            notification.second(notification.first)
        }

        fun observerGate(): Any {
            val callback = synchronized(monitor) { checkNotNull(observer) }
            val receiverField =
                generateSequence(callback.javaClass as Class<*>?) { type -> type.superclass }
                    .flatMap { type -> type.declaredFields.asSequence() }
                    .single { field -> field.type == Any::class.java && Modifier.isStatic(field.modifiers).not() }
            check(receiverField.trySetAccessible()) { "The tile observer receiver is inaccessible." }
            val receiver = checkNotNull(receiverField.get(callback))
            val gateField = receiver.javaClass.declaredFields.single { field -> field.type == Any::class.java }
            check(gateField.trySetAccessible()) { "The tile observer gate is inaccessible." }
            return checkNotNull(gateField.get(receiver))
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS: Long = 5L
        val TILE_SIZE: IntSize = IntSize(8, 8)
        val SIZE: IntSize = IntSize(16, 8)
        val CONSTRAINTS: Constraints = Constraints.fixed(SIZE.width, SIZE.height)
    }
}
