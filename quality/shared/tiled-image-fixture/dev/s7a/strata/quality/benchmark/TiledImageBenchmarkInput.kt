package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.PanZoomFit
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageCachePolicy
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf

/**
 * One source fixture compiled unchanged into portable and remote JMH consumers.
 * Owns deterministic finite inputs and subscription counters, with no planner, timer, runtime substitution, or output history.
 */
public class TiledImageBenchmarkInput(
    public val case: Case,
) {
    private val geometry = geometry(case)
    private val lodFactor = if (case == Case.Dense) 4.0 else 0.25
    private var alternate = false
    private val redeclaration = mutableStateOf(false)
    private val fit = mutableStateOf(PanZoomFit.Contain)
    private val viewport = mutableStateOf(geometry.size)
    private val marker = Marker(geometry.center)
    private val tiles = LinkedHashMap<TiledImageTileId, StateSnapshot<TiledImageTile>>()
    private val listeners = LinkedHashMap<TiledImageTileId, MutableList<(StateSnapshot<TiledImageTile>) -> Unit>>()

    /**
     * Public viewport transform owned by the actual benchmark worker.
     */
    public val navigation: PanZoomState =
        PanZoomState(
            initialCenter = geometry.center,
            initialZoom = geometry.zoom,
            minimumZoom = minOf(0.00001, geometry.zoom / 4.0),
            maximumZoom = 4_096.0,
        )

    /**
     * The same fixed logical viewport for the baseline and candidate.
     */
    public val size: IntSize get() = viewport.value

    /**
     * Exact original policy; key admission never changes the fixture's tile or image budget.
     */
    public val policy: TiledImageCachePolicy = geometry.policy

    /**
     * Actual current source observations, independently counted at their subscription boundary.
     */
    public var active: Int = 0
        private set

    /**
     * Actual opened subscriptions over this fixture lifetime.
     */
    public var opened: Long = 0
        private set

    /**
     * Actual closed subscriptions over this fixture lifetime.
     */
    public var closed: Long = 0
        private set

    /**
     * Peak actual source observations across currently attached owners of this input.
     */
    public var peakActive: Int = 0
        private set

    /**
     * Logical complete-image reservations for every current observation, including empty tiles.
     */
    public var reservedBytes: Long = 0
        private set

    /**
     * Peak logical image reservations, independent of physical image sharing between owners.
     */
    public var peakReservedBytes: Long = 0
        private set

    private val baseSource: TiledImageSource =
        object : TiledImageSource {
            override val bounds: LongRect = geometry.bounds
            override val levels: List<TiledImageLevel> = geometry.levels

            override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> =
                StateSource { listener ->
                    val initial = tiles.getOrPut(id) { StateSnapshot(StateRevision(0), initialTile(id)) }
                    listeners.getOrPut(id) { ArrayList() }.add(listener)
                    active += 1
                    opened += 1
                    val size = levels[id.level].tilePixelSize
                    val bytes = size.width.toLong() * size.height * 4L
                    reservedBytes += bytes
                    peakActive = maxOf(peakActive, active)
                    peakReservedBytes = maxOf(peakReservedBytes, reservedBytes)
                    StateSubscription(initial) {
                        active -= 1
                        closed += 1
                        reservedBytes -= bytes
                        val current = listeners.getValue(id)
                        current.remove(listener)
                        if (current.isEmpty()) {
                            listeners.remove(id)
                            tiles.remove(id)
                        }
                    }
                }
        }
    private val sourceGeneration = mutableStateOf(baseSource)

    /**
     * Current public source identity over fixed immutable geometry.
     */
    public val source: TiledImageSource get() = sourceGeneration.value

    /**
     * Full public TiledImage with an ordinary positioned overlay; no retained-node fixture replaces its implementation.
     */
    @OptIn(InternalStrataRuntimeApi::class)
    public fun element(): Element =
        evaluateComponentTree {
            redeclaration.value
            TiledImage(source, navigation, size, fit = fit.value, cachePolicy = policy) {
                Spacer(
                    Modifier.Empty
                        .size(1, 1)
                        .background(ArgbColor(0xFFAA7733.toInt()))
                        .atContentPosition(marker),
                )
            }
        }

    /**
     * Alternates bounded transform inputs inside each JMH operation, including the public invalidation cost.
     * Large-coordinate sub-ULP pans and clamped one-pixel pans are deliberate unchanged-input controls.
     */
    public fun advance(change: Change) {
        alternate = alternate.not()
        when (change) {
            Change.SameRangePan -> navigation.centerOn(geometry.center + DoubleOffset(if (alternate) 0.125 else 0.0, 0.0))
            Change.SameRangeZoom -> navigation.zoomTo(geometry.zoom * if (alternate) 1.001 else 1.0)
            Change.BoundaryPan -> navigation.centerOn(geometry.center + DoubleOffset(if (alternate) geometry.boundary else 0.0, 0.0))
            Change.LodZoom -> navigation.zoomTo(geometry.zoom * if (alternate) lodFactor else 1.0)
        }
    }

    /**
     * Rejects leaked observations while the fixture and closed host are still strongly reachable.
     */
    public fun verifyReleased() {
        check(active == 0 && opened == closed && marker.active == 0 && reservedBytes == 0L)
        check(tiles.isEmpty() && listeners.isEmpty())
    }

    /**
     * Publishes independent current tile and fixed-size overlay revisions for the complete cold protocol.
     * Callbacks enqueue snapshots; this method runs outside declaration and frame phases.
     */
    public fun revise() {
        tiles.keys.toList().forEach { id ->
            val previous = tiles.getValue(id)
            val value = if (previous.value is TiledImageTile.Ready) TiledImageTile.Empty else initialTile(id)
            val next = StateSnapshot(StateRevision(previous.revision.value + 1L), value)
            tiles[id] = next
            listeners.getValue(id).toList().forEach { listener -> listener(next) }
        }
        marker.publish(geometry.center + DoubleOffset(0.5, 0.25))
    }

    /**
     * Forces an equivalent public declaration with unchanged source, state, geometry and policy.
     */
    public fun redeclare() {
        redeclaration.value = redeclaration.value.not()
    }

    /**
     * Selects the other public fit policy without changing admission limits.
     */
    public fun changeFit() {
        fit.value = PanZoomFit.Cover
    }

    /**
     * Changes the actual public viewport by one logical column.
     */
    public fun resize() {
        viewport.value = IntSize(geometry.size.width + 1, geometry.size.height)
    }

    /**
     * Replaces source identity directly over the original source, without retaining an identity chain.
     */
    public fun replaceSource() {
        sourceGeneration.value =
            object : TiledImageSource {
                override val bounds: LongRect = geometry.bounds
                override val levels: List<TiledImageLevel> = geometry.levels

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> = baseSource.tile(id)
            }
    }

    private fun initialTile(id: TiledImageTileId): TiledImageTile {
        if (case == Case.EmptyTiles) return TiledImageTile.Empty
        if (case == Case.MixedTiles && id.level == 0 && (id.column + id.row).mod(3L) == 0L) return TiledImageTile.Empty
        val size = geometry.levels[id.level].tilePixelSize
        val color = 0xFF000000.toInt() or ((id.level + 1) shl 20) or ((id.column.toInt() and 255) shl 8) or (id.row.toInt() and 255)
        return TiledImageTile.Ready(createDrawImage(size, IntArray(size.width * size.height) { color }))
    }

    private class Marker(
        initial: DoubleOffset,
    ) : StateSource<DoubleOffset> {
        private var current = StateSnapshot(StateRevision(0), initial)
        private val listeners = ArrayList<(StateSnapshot<DoubleOffset>) -> Unit>()
        val active: Int get() = listeners.size

        override fun subscribe(observer: (StateSnapshot<DoubleOffset>) -> Unit): StateSubscription<DoubleOffset> {
            listeners.add(observer)
            return StateSubscription(current) { listeners.remove(observer) }
        }

        fun publish(value: DoubleOffset) {
            current = StateSnapshot(StateRevision(current.revision.value + 1L), value)
            listeners.toList().forEach { listener -> listener(current) }
        }
    }

    /**
     * Independent bounded geometry shapes, including empty images and a policy too small for optional key metadata.
     */
    public enum class Case {
        OneTile,
        Sparse,
        Dense,
        Fallback,
        MixedTiles,
        BudgetFallback,
        ByteFallback,
        TinyKeyBudget,
        ManyLevels,
        LargeCoordinates,
        EmptyTiles,
    }

    /**
     * Same-range placement, deliberate membership churn, and preferred-level changes remain separate rows.
     */
    public enum class Change {
        SameRangePan,
        SameRangeZoom,
        BoundaryPan,
        LodZoom,
    }

    @Suppress("LongParameterList") // Each frozen shape states source geometry, viewport, policy and the complete navigation trace.
    private data class Geometry(
        val bounds: LongRect,
        val levels: List<TiledImageLevel>,
        val size: IntSize,
        val policy: TiledImageCachePolicy,
        val center: DoubleOffset,
        val zoom: Double,
        val boundary: Double,
    )

    /**
     * Keeps 10,000 resolutions on one aligned tile envelope without relaxing public geometry validation.
     * Divisors of 18! give distinct pixel sizes with identical integral content extents; the one-entry policy admits only the final 1x1 level.
     */
    private fun alignedManyLevels(): List<TiledImageLevel> {
        val factors = listOf(2L to 16, 3L to 8, 5L to 3, 7L to 2, 11L to 1, 13L to 1, 17L to 1)
        val divisors =
            factors.fold(listOf(1L)) { previous, (prime, exponent) ->
                val powers = generateSequence(1L) { value -> value * prime }.take(exponent + 1).toList()
                previous.flatMap { divisor -> powers.map { power -> divisor * power } }
            }
        val extent = divisors.max()
        val pixels = divisors.filter { divisor -> divisor <= Int.MAX_VALUE }.sorted().take(10_000)
        check(pixels.size == 10_000)
        return pixels.asReversed().map { width -> TiledImageLevel(IntSize(width.toInt(), width.toInt()), extent / width) }
    }

    private fun geometry(case: Case): Geometry {
        val ordinary = LongRect(-48, -48, 48, 48)
        val levels = listOf(1L, 2L, 4L).map { units -> TiledImageLevel(IntSize(8, 8), units) }
        val policy = TiledImageCachePolicy(512, 64L * 1024 * 1024, 1)
        return when (case) {
            Case.OneTile -> {
                Geometry(LongRect(0, 0, 8, 8), levels.take(1), IntSize(8, 8), policy, DoubleOffset(4.0, 4.0), 2.0, 2.0)
            }

            Case.Sparse, Case.EmptyTiles -> {
                Geometry(ordinary, levels.take(1), IntSize(16, 16), policy, DoubleOffset(1.0, 1.0), 6.0, 16.0)
            }

            Case.Dense -> {
                Geometry(ordinary, listOf(TiledImageLevel(IntSize(1, 1), 1)), IntSize(16, 16), policy, DoubleOffset.Zero, 192.0 / 31.0, 2.0)
            }

            Case.Fallback, Case.MixedTiles -> {
                Geometry(ordinary, levels, IntSize(16, 16), policy, DoubleOffset(1.0, 1.0), 6.0, 16.0)
            }

            Case.BudgetFallback -> {
                Geometry(ordinary, levels, IntSize(16, 16), TiledImageCachePolicy(16, 64L * 1024 * 1024, 0), DoubleOffset(1.0, 1.0), 6.0, 16.0)
            }

            Case.ByteFallback -> {
                Geometry(ordinary, levels, IntSize(16, 16), TiledImageCachePolicy(512, 4_096, 0), DoubleOffset(1.0, 1.0), 6.0, 16.0)
            }

            Case.TinyKeyBudget -> {
                Geometry(LongRect(0, 0, 1, 1), List(24) { index -> TiledImageLevel(IntSize(1, 1), 1L shl index) }, IntSize(1, 1), TiledImageCachePolicy(24, 96, 0), DoubleOffset(0.5, 0.5), 1.0, 1.0)
            }

            Case.ManyLevels -> {
                val many = alignedManyLevels()
                Geometry(LongRect(0, 0, 1, 1), many, IntSize(1, 1), TiledImageCachePolicy(1, 32, 0), DoubleOffset(0.5, 0.5), 1.0 / many.last().contentUnitsPerPixel, 1.0)
            }

            Case.LargeCoordinates -> {
                Geometry(LongRect(9_007_199_254_740_984, 0, 9_007_199_254_741_008, 6), listOf(TiledImageLevel(IntSize(6, 6), 1)), IntSize(1, 1), TiledImageCachePolicy(8, 2_048, 0), DoubleOffset(9_007_199_254_740_996.0, 3.0), 1_024.0, 6.0)
            }
        }
    }
}
