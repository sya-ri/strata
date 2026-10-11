@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomMetrics
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.PanZoomStateObserver
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.panZoom
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.spi.RuntimeUiSession
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Frozen supplemental publication matrix with 0, 1, 16 and 128 notified observers.
 * State operations include every notification; setup, priming and teardown stay outside measurement.
 * Geometry has one additional suppressed origin, churn has one transient notified observer, and the session has two retained observers for its viewport and tile layer.
 * The separate session control includes frame construction and headless rasterization, so its cost cannot be attributed solely to membership snapshots.
 */
public open class PanZoomPublicationBenchmark {
    /**
     * Measures the existing equal-metrics early return.
     */
    @Benchmark
    public fun equalZoom(scene: Scene): Double = scene.equalZoom()

    /**
     * Publishes alternating changed zoom values to fixed membership.
     */
    @Benchmark
    public fun changedZoom(scene: Scene): Double = scene.changedZoom()

    /**
     * Publishes alternating centers within established large bounds.
     */
    @Benchmark
    public fun changedPan(scene: Scene): DoubleOffset = scene.changedPan()

    /**
     * Includes admission, changed publication and release in each operation.
     */
    @Benchmark
    public fun observerChurn(scene: Scene): Double = scene.churn()

    /**
     * Changes valid geometry while suppressing only the same known origin.
     */
    @Benchmark
    public fun changedGeometry(scene: GeometryScene): PanZoomMetrics = scene.changedGeometry()

    /**
     * Measures the full retained tiled-image frame and fresh CPU raster control.
     */
    @Benchmark
    public fun tiledSession(scene: SessionScene): HeadlessImage = scene.changedFrame()

    /**
     * Owns fixed notification counters and one state on each JMH worker.
     */
    @State(Scope.Thread)
    public open class Scene {
        /**
         * Exact stable membership; churn temporarily adds one observer.
         */
        @JvmField
        @Param("0", "1", "16", "128")
        public var observerCount: Int = 0

        private lateinit var publication: Publication
        private var zoomed = false
        private var panned = false
        private var churnCalls = 0L

        /**
         * Establishes bounds, counters and a warm publication outside timing.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            publication = Publication(observerCount)
            zoomed = false
            panned = false
            churnCalls = 0L
        }

        /**
         * Returns equal zoom without changing counters or membership.
         */
        public fun equalZoom(): Double = publication.state.zoomTo(publication.state.metrics.zoom)

        /**
         * Alternates between zoom three and two, including callback delivery.
         */
        public fun changedZoom(): Double {
            zoomed = zoomed.not()
            return publication.state.zoomTo(if (zoomed) 3.0 else 2.0)
        }

        /**
         * Alternates two finite centers which are inside both zooms' bounds.
         */
        public fun changedPan(): DoubleOffset {
            panned = panned.not()
            return publication.state.centerOn(if (panned) DoubleOffset(513.0, 513.0) else DoubleOffset(512.0, 512.0))
        }

        /**
         * Adds and releases one token around a changed publication.
         */
        public fun churn(): Double {
            val observer = publication.state.observe { churnCalls += 1L }
            return try {
                changedZoom()
            } finally {
                observer.close()
            }
        }

        /**
         * Checks exact counts and current scalar values without collecting callback history.
         */
        public fun verifyWork() {
            val before = publication.calls
            repeat(100) { equalZoom() }
            check(publication.calls == before)
            repeat(100) {
                changedZoom()
                changedPan()
                churn()
                publication.verifyValues()
            }
            check(publication.calls == before + 300L * observerCount)
            check(churnCalls == 100L)
        }

        /**
         * Releases every retained token outside timing.
         */
        @TearDown(Level.Trial)
        public fun close() {
            publication.close()
        }
    }

    /**
     * Owns one suppressed geometry token in addition to the parameter's notified observers.
     */
    @State(Scope.Thread)
    public open class GeometryScene {
        /**
         * Number of notified navigation observers, excluding the known geometry origin.
         */
        @JvmField
        @Param("0", "1", "16", "128")
        public var observerCount: Int = 0

        private lateinit var publication: Publication
        private lateinit var origin: PanZoomStateObserver
        private var wider = false
        private var originCalls = 0L

        /**
         * Acquires geometry ownership and primes its ordered membership outside timing.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            publication = Publication(observerCount)
            wider = false
            originCalls = 0L
            origin = publication.state.observe { originCalls += 1L }
            changedGeometry()
            changedGeometry()
        }

        /**
         * Alternates valid viewport extents without changing the live origin.
         */
        public fun changedGeometry(): PanZoomMetrics {
            wider = wider.not()
            publication.state.updateGeometry(BOUNDS, if (wider) IntSize(80, 64) else VIEWPORT, PanZoomFit.Cover, origin)
            return publication.state.metrics
        }

        /**
         * Requires one delivery per navigation observer and no geometry feedback.
         */
        public fun verifyWork() {
            val before = publication.calls
            repeat(100) {
                changedGeometry()
                publication.verifyValues()
            }
            check(publication.calls == before + 100L * observerCount)
            check(originCalls == 0L)
        }

        /**
         * Releases the origin and every navigation token outside timing.
         */
        @TearDown(Level.Trial)
        public fun close() {
            origin.close()
            publication.close()
        }
    }

    /**
     * Owns one primed tiled-image session, with independent navigation observers.
     */
    @State(Scope.Thread)
    public open class SessionScene {
        /**
         * Navigation membership, in addition to the retained viewport and tile-layer observers.
         */
        @JvmField
        @Param("0", "1", "16", "128")
        public var observerCount: Int = 0

        private lateinit var publication: Publication
        private lateinit var source: TileSource
        private lateinit var session: RuntimeUiSession
        private val constraints = Constraints.fixed(VIEWPORT.width, VIEWPORT.height)
        private var zoomed = false

        /**
         * Attaches and primes the fixed immutable tile before collection starts.
         */
        @Setup(Level.Trial)
        public fun setUp() {
            publication = Publication(observerCount)
            source = TileSource()
            zoomed = false
            session =
                createRuntimeUiSession {
                    evaluateComponentTree {
                        TiledImage(
                            source,
                            publication.state,
                            VIEWPORT,
                            fit = PanZoomFit.Cover,
                            modifier = Modifier.panZoom(publication.state),
                            cachePolicy = TiledImageCachePolicy(maxEntries = 1, maxBytes = 1024L, overscanTiles = 0),
                        )
                    }
                }
            session.attach()
            session.frame(constraints)
        }

        /**
         * Includes publication, retained frame work and the complete 64-by-64 raster.
         */
        public fun changedFrame(): HeadlessImage {
            zoomed = zoomed.not()
            publication.state.zoomTo(if (zoomed) 3.0 else 2.0)
            val frame = session.frame(constraints)
            return rasterizeHeadless(frame.drawCommands, frame.size)
        }

        /**
         * Checks unchanged pixels, bounded source ownership and resumable detach.
         */
        public fun verifyWork() {
            val before = publication.calls
            repeat(10) {
                val image = changedFrame()
                check(image.size == VIEWPORT)
                for (y in 0 until VIEWPORT.height) {
                    for (x in 0 until VIEWPORT.width) check(image.argbAt(x, y) == COLOR)
                }
                publication.verifyValues()
                val frame = session.frame(constraints)
                check(session.frame(constraints) === frame)
                check(source.active == 1 && source.requests == 1)
            }
            check(publication.calls == before + 10L * observerCount)
            session.detach()
            check(source.active == 0)
            session.attach()
            session.frame(constraints)
            check(source.active == 1)
        }

        /**
         * Requires terminal subscription release while leaving caller-owned state usable.
         */
        @TearDown(Level.Trial)
        public fun close() {
            session.close()
            session.close()
            check(source.active == 0 && source.subscriptions == source.closes)
            publication.close()
        }
    }

    /**
     * Discovers and verifies this supplemental matrix through the existing testkit entry point.
     */
    public companion object {
        private val BOUNDS = LongRect(0L, 0L, 1024L, 1024L)
        private val VIEWPORT = IntSize(64, 64)
        private val COLOR = 0xFF336699.toInt()

        /**
         * Checks all twenty-four generated cases and deterministic counters outside timing.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(PanZoomPublicationBenchmark::class.java), setOf("avgt")).size == 24)
            for (count in listOf(0, 1, 16, 128)) {
                val scene = Scene().apply { observerCount = count }
                scene.setUp()
                try {
                    scene.verifyWork()
                } finally {
                    scene.close()
                }
                val geometry = GeometryScene().apply { observerCount = count }
                geometry.setUp()
                try {
                    geometry.verifyWork()
                } finally {
                    geometry.close()
                }
                val session = SessionScene().apply { observerCount = count }
                session.setUp()
                try {
                    session.verifyWork()
                } finally {
                    session.close()
                }
            }
        }
    }

    /**
     * Keeps only fixed scalar counters and the current value for each observer.
     */
    private class Publication(count: Int) : AutoCloseable {
        val state = PanZoomState()
        private val counts = LongArray(count)
        private val zooms = DoubleArray(count)
        private val centers = DoubleArray(count)
        private val handles: List<PanZoomStateObserver>

        val calls: Long
            get() = counts.sum()

        init {
            val geometry = state.observe { _ -> }
            state.updateGeometry(BOUNDS, VIEWPORT, PanZoomFit.Cover, geometry)
            geometry.close()
            handles =
                List(count) { index ->
                    state.observe { metrics ->
                        counts[index] += 1L
                        zooms[index] = metrics.zoom
                        centers[index] = metrics.center.x
                    }
                }
            state.zoomTo(2.0)
        }

        /**
         * Requires every notified observer's last scalar values to match current authoritative metrics.
         */
        fun verifyValues() {
            check(zooms.all { it == state.metrics.zoom })
            check(centers.all { it == state.metrics.center.x })
        }

        override fun close() {
            handles.forEach(PanZoomStateObserver::close)
            val before = calls
            state.zoomTo(4.0)
            check(calls == before)
        }
    }

    /**
     * Supplies one immutable uniform tile without retaining callbacks or request history.
     */
    private class TileSource : TiledImageSource {
        override val bounds: LongRect = BOUNDS
        override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(16, 16), 64L))
        private val image = createDrawImage(IntSize(16, 16), IntArray(256) { COLOR })
        var requests = 0
        var active = 0
        var subscriptions = 0
        var closes = 0

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
            check(id == TiledImageTileId(0, 0L, 0L))
            requests += 1
            return StateSource {
                active += 1
                subscriptions += 1
                StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Ready(image))) {
                    active -= 1
                    closes += 1
                }
            }
        }
    }
}
