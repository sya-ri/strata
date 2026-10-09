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
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.node.DeclarationProjectionNode
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.state.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Compares current-grid reuse with a linear source-grid oracle and independent subscription transactions.
 */
internal class TiledImageTopologyTest {
    @Test
    fun layoutAndDeclarationReuseOnlyTopologyWhileRefreshingPlacementAndTileRevisions() {
        val source = Source(LongRect(-48, -48, 48, 48), standardLevels())
        val state = PanZoomState(initialCenter = DoubleOffset(1.0, 1.0), initialZoom = 6.0)
        val size = IntSize(16, 16)
        val policy = TiledImageCachePolicy(128, 1_048_576, 1)
        val session = session(source, state, size, policy)
        session.attach()
        val first = session.frame(constraints(size))
        val layer = layer(session)
        val grid = topology(layer)
        val requests = source.events.toList()
        val previousDestinations = samples(first).map(DrawCommand.SampledImage::destination)

        state.panBy(DoubleOffset(0.125, 0.125))
        session.projectDeclarations { it.identity }
        assertSame(grid, topology(layer))
        val moved = session.frame(constraints(size))
        assertSame(grid, topology(layer))
        assertEquals(requests, source.events)
        assertTrue(previousDestinations != samples(moved).map(DrawCommand.SampledImage::destination))
        assertFrame(source, state, size, policy, moved)

        state.zoomBy(1.001)
        session.projectDeclarations { it.identity }
        val zoomed = session.frame(constraints(size))
        assertSame(grid, topology(layer))
        assertEquals(requests, source.events)
        assertFrame(source, state, size, policy, zoomed)
        assertEquals(previousDestinations, samples(first).map(DrawCommand.SampledImage::destination))

        val changed = TiledImageTopologyOracle.plan(source, state.metrics, size, policy).painted.first { id -> id.level == 0 }
        val replacement = createDrawImage(source.levels[changed.level].tilePixelSize, IntArray(64) { 0xFFABCDEF.toInt() })
        source.publish(changed, TiledImageTile.Ready(replacement))
        val revised = session.frame(constraints(size))
        assertSame(grid, topology(layer))
        assertTrue(samples(revised).any { sample -> sample.image === replacement })
        assertTrue(samples(first).none { sample -> sample.image === replacement })
        assertEquals(requests, source.events)
        session.close()
        assertEmptyTopology(layer)
        assertTrue(source.active.isEmpty())
    }

    @Test
    fun finiteGridOracleChecksNegativePartialEdgesFallbackAdmissionAndBoundaryChurn() {
        val policies =
            listOf(
                TiledImageCachePolicy(128, 1_048_576, 0),
                TiledImageCachePolicy(128, 1_048_576, 1),
                TiledImageCachePolicy(10, 2_560, 1),
                TiledImageCachePolicy(100, 4_096, 0),
            )
        for (policy in policies) {
            val source = Source(LongRect(-43, -29, 45, 31), standardLevels())
            val state = PanZoomState(initialCenter = DoubleOffset(1.0, 1.0), initialZoom = 6.0)
            val size = IntSize(16, 12)
            val session = session(source, state, size, policy)
            session.attach()
            var expectedActive = linkedSetOf<TiledImageTileId>()
            var previous: TiledImageTopologyOracle.Plan? = null
            var previousGrid: Any? = null
            val expectedEvents = ArrayList<Event>()
            for (step in 0 until 64) {
                state.centerOn(DoubleOffset((step % 8 - 3) * 7.75 + 0.125, (step / 8 - 3) * 5.5 + 0.25))
                state.zoomTo(if (step % 3 == 0) 1.0 else 6.0)
                val frame = session.frame(constraints(size))
                val expected = TiledImageTopologyOracle.plan(source, state.metrics, size, policy)
                val removed = expectedActive.filter { id -> (id in expected.required).not() }
                expectedEvents.addAll(removed.asReversed().map(Event::Close))
                expectedActive.removeAll(removed.toSet())
                expected.required.filter { id -> (id in expectedActive).not() }.forEach { id ->
                    expectedEvents.add(Event.Open(id))
                    expectedActive.add(id)
                }
                assertEquals(expectedEvents, source.events)
                assertEquals(expectedActive.toList(), source.active.keys.toList())
                assertFrame(source, state, size, policy, frame)
                val grid = topology(layer(session))
                if (previous == expected) assertSame(previousGrid, grid) else assertNotSame(previousGrid, grid)
                previous = expected
                previousGrid = grid
                assertTrue(source.active.size <= policy.maxEntries)
            }
            expectedEvents.addAll(expectedActive.toList().asReversed().map(Event::Close))
            session.close()
            assertEquals(expectedEvents, source.events)
            assertTrue(source.active.isEmpty())
        }
    }

    @Test
    fun largeCoordinatesAndSubUlpViewportsUseExactHalfOpenRangesOnRepeatedZooms() {
        val bounds =
            listOf(
                LongRect(9_007_199_254_740_984, 0, 9_007_199_254_741_008, 6),
                LongRect(-9_007_199_254_741_008, 0, -9_007_199_254_740_984, 6),
            )
        for (area in bounds) {
            val source = Source(area, listOf(TiledImageLevel(IntSize(6, 6), 1)))
            val state =
                PanZoomState(
                    initialCenter = DoubleOffset((area.left + 12).toDouble(), 3.0),
                    initialZoom = 1_024.0,
                    maximumZoom = 2_048.0,
                )
            val size = IntSize(1, 1)
            val policy = TiledImageCachePolicy(8, 2_048, 0)
            val session = session(source, state, size, policy)
            session.attach()
            val first = session.frame(constraints(size))
            val grid = topology(layer(session))
            assertFrame(source, state, size, policy, first)
            repeat(16) { index ->
                state.zoomTo(if (index % 2 == 0) 1_025.0 else 1_024.0)
                val frame = session.frame(constraints(size))
                assertSame(grid, topology(layer(session)))
                assertFrame(source, state, size, policy, frame)
            }
            session.close()
        }
    }

    @Test
    fun sourceStateViewportAndPolicyReplacementInvalidateEvenEqualRanges() {
        val source = Source(LongRect(0, 0, 8, 8), listOf(TiledImageLevel(IntSize(8, 8), 1)))
        val initial = Configuration(source, PanZoomState(), IntSize(8, 8), TiledImageCachePolicy(8, 2_048, 0))
        val configuration = mutableStateOf(initial)
        val session =
            UiSession(TestOwnerDispatcher()) {
                val current = configuration.value
                evaluateComponentTree { TiledImage(current.source, current.state, current.size, cachePolicy = current.policy) }
            }
        session.attach()
        val original = session.frame(constraints(initial.size))
        val layer = layer(session)
        var previous = topology(layer)
        val replacements =
            listOf(
                initial.copy(state = PanZoomState()),
                initial.copy(size = IntSize(9, 8)),
                initial.copy(policy = TiledImageCachePolicy(9, 2_049, 0)),
                initial.copy(source = Source(source.bounds, source.levels)),
            )
        for (replacement in replacements) {
            configuration.value = replacement
            val frame = session.frame(constraints(replacement.size))
            val current = topology(layer)
            assertNotSame(previous, current)
            previous = current
            assertFrame(replacement.source, replacement.state, replacement.size, replacement.policy, frame)
        }
        assertEquals(1, source.events.count { it is Event.Close })
        val originalPixels = samples(original).single().image.copyArgb()
        session.detach()
        assertEmptyTopology(layer)
        session.attach()
        session.frame(constraints(configuration.value.size))
        assertNotSame(previous, topology(layer))
        session.close()
        assertEmptyTopology(layer)
        assertTrue(configuration.value.source.active.isEmpty())
        assertEquals(originalPixels.toList(), samples(original).single().image.copyArgb().toList())
    }

    @Test
    fun smallBytePolicyAndManyLevelsKeepOriginalAdmissionWithoutRetainingOversizedKeys() {
        val source = Source(LongRect(0, 0, 1, 1), List(24) { index -> TiledImageLevel(IntSize(1, 1), 1L shl index) })
        val state = PanZoomState(initialCenter = DoubleOffset(0.5, 0.5))
        val size = IntSize(1, 1)
        val policy = TiledImageCachePolicy(24, 96, 0)
        val session = session(source, state, size, policy)
        session.attach()
        session.frame(constraints(size))
        val layer = layer(session)
        val previous = topology(layer)
        assertNull(field(previous, "key"))
        state.zoomBy(1.001)
        session.frame(constraints(size))
        assertNotSame(previous, topology(layer))
        assertNull(field(topology(layer), "key"))
        assertEquals(24, source.active.size)
        assertEquals(24, source.events.size)
        session.close()

        val many = Source(LongRect(0, 0, 1, 1), alignedManyLevels())
        val initialZoom = 1.0 / many.levels.last().contentUnitsPerPixel
        val zoom = PanZoomState(initialCenter = DoubleOffset(0.5, 0.5), initialZoom = initialZoom, minimumZoom = initialZoom / 4.0)
        val bounded = session(many, zoom, size, TiledImageCachePolicy(1, 32, 0))
        bounded.attach()
        bounded.frame(constraints(size))
        val current = topology(layer(bounded))
        val key = checkNotNull(field(current, "key"))
        assertEquals(1, (field(key, "requiredRanges") as List<*>).size)
        assertEquals(listOf(TiledImageTileId(9_999, 0, 0)), many.active.keys.toList())
        bounded.close()
    }

    @Test
    fun failedSubscriptionAndRemovalExposeNoReusableTopologyBeforeExternalCallbacks() {
        val source = Source(LongRect(-48, -48, 48, 48), standardLevels())
        val state = PanZoomState(initialCenter = DoubleOffset(1.0, 1.0), initialZoom = 6.0)
        val size = IntSize(16, 16)
        val session = session(source, state, size, TiledImageCachePolicy(128, 1_048_576, 1))
        session.attach()
        val retained = session.frame(constraints(size))
        val layer = layer(session)
        val failure = IllegalStateException("subscription failed")
        source.beforeClose = { assertEmptyTopology(layer) }
        source.beforeOpen = { id ->
            assertEmptyTopology(layer)
            if (id.column == 3L && id.level == 0) throw failure
        }
        state.panBy(DoubleOffset(16.0, 0.0))
        assertSame(failure, assertThrows(IllegalStateException::class.java) { session.frame(constraints(size)) })
        assertEmptyTopology(layer)
        assertTrue(source.active.isEmpty())
        assertTrue(samples(retained).isNotEmpty())
        session.close()
    }

    @Test
    fun independentOwnersAndLongHistoryRetainOnlyTheirCurrentBoundedGrid() {
        val source = Source(LongRect(-48, -48, 48, 48), standardLevels())
        val policy = TiledImageCachePolicy(128, 1_048_576, 1)
        val size = IntSize(16, 16)
        val firstState = PanZoomState(initialCenter = DoubleOffset(1.0, 1.0), initialZoom = 6.0)
        val secondState = PanZoomState(initialCenter = DoubleOffset(1.0, 1.0), initialZoom = 6.0)
        val first = session(source, firstState, size, policy)
        val second = session(source, secondState, size, policy)
        first.attach()
        second.attach()
        first.frame(constraints(size))
        second.frame(constraints(size))
        val firstLayer = layer(first)
        val secondLayer = layer(second)
        val secondGrid = topology(secondLayer)
        assertNotSame(topology(firstLayer), secondGrid)
        repeat(2_000) { index ->
            firstState.centerOn(DoubleOffset((index % 8 - 3) * 10.0 + 0.125, (index / 8 % 8 - 3) * 8.0 + 0.25))
            first.frame(constraints(size))
            val current = topology(firstLayer)
            val ids = field(current, "requiredIds") as List<*>
            assertTrue(ids.size <= policy.maxEntries)
            val key = checkNotNull(field(current, "key"))
            val ranges = field(key, "requiredRanges") as List<*>
            assertTrue(ranges.size <= policy.maxEntries)
            assertTrue(ranges.size.toLong() * 32L <= policy.maxBytes)
            assertSame(secondGrid, topology(secondLayer))
        }
        first.close()
        assertEmptyTopology(firstLayer)
        assertSame(secondGrid, topology(secondLayer))
        second.close()
        assertEmptyTopology(secondLayer)
        assertTrue(source.active.isEmpty())
    }

    @Test
    fun equivalentRedeclarationAndBothFitModesPreserveAnIdenticalGridWithCurrentPlacement() {
        val source = Source(LongRect(0, 0, 16, 8), listOf(TiledImageLevel(IntSize(8, 8), 1)))
        val navigation = PanZoomState(initialCenter = DoubleOffset(8.25, 4.0), initialZoom = 4.0)
        val fit = mutableStateOf(PanZoomFit.Contain)
        val rebuild = mutableStateOf(false)
        val size = IntSize(16, 16)
        val policy = TiledImageCachePolicy(8, 2_048, 1)
        val session =
            UiSession(TestOwnerDispatcher()) {
                rebuild.value
                evaluateComponentTree { TiledImage(source, navigation, size, fit = fit.value, cachePolicy = policy) }
            }
        session.attach()
        val first = session.frame(constraints(size))
        val layer = layer(session)
        val grid = topology(layer)
        val events = source.events.toList()
        rebuild.value = true
        session.frame(constraints(size))
        assertSame(grid, topology(layer))
        fit.value = PanZoomFit.Cover
        val covered = session.frame(constraints(size))
        assertSame(grid, topology(layer))
        assertEquals(events, source.events)
        assertFrame(source, navigation, size, policy, covered)
        assertTrue(samples(first).map(DrawCommand.SampledImage::destination) != samples(covered).map(DrawCommand.SampledImage::destination))
        session.close()
    }

    @Test
    fun knownGeometryAndAdmissionChecksStillRunBeforeAReusableHit() {
        for (known in listOf(false, true)) {
            val source = Source(LongRect(0, 0, 8, 8), listOf(TiledImageLevel(IntSize(8, 8), 1)))
            val navigation = PanZoomState()
            val size = IntSize(8, 8)
            val session = session(source, navigation, size, TiledImageCachePolicy(8, 2_048, 0))
            session.attach()
            session.frame(constraints(size))
            val layer = layer(session)
            val metrics = navigation.metrics.copy(geometryKnown = known, viewportSize = if (known) IntSize(9, 8) else size)
            val metricsField = navigation.javaClass.getDeclaredField("currentMetrics")
            metricsField.isAccessible = true
            metricsField.set(navigation, metrics)
            assertThrows(IllegalStateException::class.java) { (layer as DeclarationProjectionNode).prepareDeclaration() }
            assertEmptyTopology(layer)
            session.close()
            assertTrue(source.active.isEmpty())
        }

        val source = Source(LongRect(0, 0, 8, 8), listOf(TiledImageLevel(IntSize(8, 8), 1)))
        val policy = mutableStateOf(TiledImageCachePolicy(8, 2_048, 0))
        val navigation = PanZoomState()
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree { TiledImage(source, navigation, IntSize(8, 8), cachePolicy = policy.value) }
            }
        session.attach()
        val previous = session.frame(Constraints.fixed(8, 8))
        val layer = layer(session)
        val opened = source.events.count { it is Event.Open }
        source.beforeClose = { assertEmptyTopology(layer) }
        policy.value = TiledImageCachePolicy(1, 1, 0)
        assertThrows(IllegalStateException::class.java) { session.frame(Constraints.fixed(8, 8)) }
        assertEmptyTopology(layer)
        assertEquals(opened, source.events.count { it is Event.Open })
        assertTrue(source.active.isEmpty())
        assertTrue(samples(previous).isNotEmpty())
        session.close()
    }

    @Test
    fun emptyVisibleIntersectionClearsInstalledMembershipAndKeepsAnIndependentEmptyPlan() {
        val source = Source(LongRect(0, 0, 8, 8), listOf(TiledImageLevel(IntSize(8, 8), 1)))
        val navigation = PanZoomState()
        val size = IntSize(8, 8)
        val policy = TiledImageCachePolicy(8, 2_048, 0)
        val session = session(source, navigation, size, policy)
        session.attach()
        val original = session.frame(constraints(size))
        val layer = layer(session)
        source.beforeClose = { assertEmptyTopology(layer) }
        val metrics = navigation.metrics.copy(center = DoubleOffset(1_000.0, 1_000.0))
        val reflected = navigation.javaClass.getDeclaredField("currentMetrics")
        reflected.isAccessible = true
        reflected.set(navigation, metrics)
        (layer as DeclarationProjectionNode).prepareDeclaration()
        val expected = TiledImageTopologyOracle.plan(source, metrics, size, policy)
        assertTrue(expected.required.isEmpty() && expected.painted.isEmpty())
        assertTrue((field(topology(layer), "requiredIds") as List<*>).isEmpty())
        assertTrue((field(topology(layer), "cells") as List<*>).isEmpty())
        assertTrue(source.active.isEmpty())
        assertTrue(samples(original).isNotEmpty())
        session.close()
        assertEmptyTopology(layer)
    }

    @Test
    fun cachedGridStillRejectsUnrepresentableCurrentFloatDestinationsAndReleasesBeforeCallbacks() {
        val source = Source(LongRect(0, 0, 8, 8), listOf(TiledImageLevel(IntSize(8, 8), 1)))
        val navigation = PanZoomState(initialCenter = DoubleOffset(4.0, 4.0), maximumZoom = Double.MAX_VALUE)
        val size = IntSize(8, 8)
        val session = session(source, navigation, size, TiledImageCachePolicy(8, 2_048, 0))
        session.attach()
        val original = session.frame(constraints(size))
        val layer = layer(session)
        val grid = topology(layer)
        navigation.zoomTo(Double.MAX_VALUE)
        session.projectDeclarations { it.identity }
        assertSame(grid, topology(layer))
        source.beforeClose = { assertEmptyTopology(layer) }
        assertThrows(IllegalArgumentException::class.java) { session.frame(constraints(size)) }
        assertEmptyTopology(layer)
        assertTrue(source.active.isEmpty())
        assertTrue(samples(original).isNotEmpty())
        session.close()
    }

    @Test
    fun terminalCallbackFailuresKeepReverseOrderSuppressionAndIdempotentClearedOwnership() {
        val source = Source(LongRect(-48, -48, 48, 48), standardLevels())
        val navigation = PanZoomState(initialCenter = DoubleOffset(1.0, 1.0), initialZoom = 6.0)
        val session = session(source, navigation, IntSize(16, 16), TiledImageCachePolicy(128, 1_048_576, 1))
        session.attach()
        session.frame(Constraints.fixed(16, 16))
        val layer = layer(session)
        val required = source.active.keys.toList()
        val failures = listOf(IllegalArgumentException("last observation"), IllegalArgumentException("penultimate observation"))
        var callback = 0
        source.beforeClose = {
            assertEmptyTopology(layer)
            val position = callback++
            if (position < failures.size) throw failures[position]
        }
        assertSame(failures[0], assertThrows(IllegalArgumentException::class.java) { session.close() })
        assertEquals(listOf(failures[1]), failures[0].suppressed.toList())
        assertEquals(required.asReversed(), source.events.filterIsInstance<Event.Close>().map(Event.Close::id))
        assertEquals(required.size, callback)
        assertTrue(source.active.isEmpty())
        assertEmptyTopology(layer)
        session.close()
        assertEquals(required.size, callback)
    }

    private fun assertFrame(
        source: Source,
        state: PanZoomState,
        size: IntSize,
        policy: TiledImageCachePolicy,
        frame: RuntimeUiFrame,
    ) {
        val expected = TiledImageTopologyOracle.plan(source, state.metrics, size, policy)
        val commands = samples(frame)
        assertEquals(expected.painted.size, commands.size)
        expected.painted.zip(commands).forEach { (id, command) ->
            assertSame((source.latest.getValue(id) as TiledImageTile.Ready).image, command.image)
            assertEquals(TiledImageTopologyOracle.destination(source, id, state.metrics, size), command.destination)
        }
    }

    private fun session(
        source: Source,
        state: PanZoomState,
        size: IntSize,
        policy: TiledImageCachePolicy,
    ): UiSession = UiSession(TestOwnerDispatcher()) { evaluateComponentTree { TiledImage(source, state, size, cachePolicy = policy) } }

    private fun constraints(size: IntSize): Constraints = Constraints.fixed(size.width, size.height)

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

    private fun standardLevels(): List<TiledImageLevel> = listOf(1L, 2L, 4L).map { units -> TiledImageLevel(IntSize(8, 8), units) }

    private fun samples(frame: RuntimeUiFrame): List<DrawCommand.SampledImage> = frame.drawCommands.filterIsInstance<DrawCommand.SampledImage>()

    private fun layer(session: UiSession): Any {
        val tree = checkNotNull(field(session, "tree"))
        val root = checkNotNull(field(tree, "root")) as RetainedNode
        val type = Class.forName("dev.s7a.strata.component.TiledImageTileLayerElement\$Node")
        val pending = ArrayDeque<RetainedNode>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val next = pending.removeLast()
            if (type.isInstance(next.node)) return next.node
            pending.addAll(next.children)
        }
        error("The real retained tile layer is absent.")
    }

    private fun topology(layer: Any): Any = checkNotNull(field(checkNotNull(field(layer, "plan")), "topology"))

    private fun assertEmptyTopology(layer: Any) {
        val current = topology(layer)
        assertNull(field(current, "key"))
        assertTrue((field(current, "requiredIds") as List<*>).isEmpty())
        assertTrue((field(current, "cells") as List<*>).isEmpty())
    }

    private fun field(
        instance: Any,
        name: String,
    ): Any? {
        val reflected = instance.javaClass.getDeclaredField(name)
        reflected.isAccessible = true
        return reflected.get(instance)
    }

    private data class Configuration(
        val source: Source,
        val state: PanZoomState,
        val size: IntSize,
        val policy: TiledImageCachePolicy,
    )

    private sealed interface Event {
        data class Open(
            val id: TiledImageTileId,
        ) : Event

        data class Close(
            val id: TiledImageTileId,
        ) : Event
    }

    private class Source(
        override val bounds: LongRect,
        override val levels: List<TiledImageLevel>,
    ) : TiledImageSource {
        val events: MutableList<Event> = ArrayList()
        val active: MutableMap<TiledImageTileId, MutableList<(StateSnapshot<TiledImageTile>) -> Unit>> = LinkedHashMap()
        val latest: MutableMap<TiledImageTileId, TiledImageTile> = LinkedHashMap()
        var beforeOpen: ((TiledImageTileId) -> Unit)? = null
        var beforeClose: (() -> Unit)? = null
        private var revision = 0L

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
            beforeOpen?.invoke(id)
            events.add(Event.Open(id))
            val value =
                latest.getOrPut(id) {
                    val size = levels[id.level].tilePixelSize
                    val color = 0xFF000000.toInt() or ((id.level + 1) shl 20) or ((id.column.toInt() and 255) shl 8) or (id.row.toInt() and 255)
                    TiledImageTile.Ready(createDrawImage(size, IntArray(size.width * size.height) { color }))
                }
            return StateSource { listener ->
                active.getOrPut(id) { ArrayList() }.add(listener)
                StateSubscription(StateSnapshot(StateRevision(revision), value)) {
                    val listeners = active.getValue(id)
                    listeners.remove(listener)
                    if (listeners.isEmpty()) active.remove(id)
                    events.add(Event.Close(id))
                    beforeClose?.invoke()
                }
            }
        }

        fun publish(
            id: TiledImageTileId,
            value: TiledImageTile,
        ) {
            revision += 1
            latest[id] = value
            active.getValue(id).toList().forEach { listener -> listener(StateSnapshot(StateRevision(revision), value)) }
        }
    }
}
