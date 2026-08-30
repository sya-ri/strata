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
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.properties.ReadWriteProperty

/**
 * Verifies tiled-image planning, revision cutoffs, source replacement, retained overlay isolation, and cleanup.
 */
internal class TiledImageSessionTest {
    @Test
    fun negativeTileRangesPaintRowMajorAndOneRevisionReplacesOnlyItsImage() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val source =
            RecordingSource(LongRect(-16L, -8L, 16L, 8L), listOf(level)) { id ->
                TiledImageTile.Ready(image(level.tilePixelSize, color(id)))
            }
        val session = session(source, PanZoomState(), IntSize(32, 16), policy(32))
        session.attach()

        val first = session.frame(Constraints.fixed(32, 16))
        val expectedIds =
            listOf(
                TiledImageTileId(0, -2L, -1L),
                TiledImageTileId(0, -1L, -1L),
                TiledImageTileId(0, 0L, -1L),
                TiledImageTileId(0, 1L, -1L),
                TiledImageTileId(0, -2L, 0L),
                TiledImageTileId(0, -1L, 0L),
                TiledImageTileId(0, 0L, 0L),
                TiledImageTileId(0, 1L, 0L),
            )
        assertEquals(expectedIds, source.requests)
        assertEquals(
            listOf(DrawCommand.PushClip(IntRect(0, 0, 32, 16)), DrawCommand.PopClip),
            first.drawCommands.filter { command -> command is DrawCommand.PushClip || command is DrawCommand.PopClip },
        )
        val firstSamples = samples(first)
        assertEquals(expectedIds.size, firstSamples.size)
        assertEquals(
            listOf(
                FloatRect(0.0f, 0.0f, 8.0f, 8.0f),
                FloatRect(8.0f, 0.0f, 16.0f, 8.0f),
                FloatRect(16.0f, 0.0f, 24.0f, 8.0f),
                FloatRect(24.0f, 0.0f, 32.0f, 8.0f),
                FloatRect(0.0f, 8.0f, 8.0f, 16.0f),
                FloatRect(8.0f, 8.0f, 16.0f, 16.0f),
                FloatRect(16.0f, 8.0f, 24.0f, 16.0f),
                FloatRect(24.0f, 8.0f, 32.0f, 16.0f),
            ),
            firstSamples.map(DrawCommand.SampledImage::destination),
        )
        assertTrue(firstSamples.all { sample -> sample.alphaCutoff == 0.0f })

        val changedId = expectedIds[2]
        val changedImage = image(level.tilePixelSize, 0xFFABCDEF.toInt())
        source.history(changedId).publish(TiledImageTile.Ready(changedImage))
        val secondSamples = samples(session.frame(Constraints.fixed(32, 16)))
        secondSamples.forEachIndexed { index, sample ->
            if (index == 2) {
                assertSame(changedImage, sample.image)
                assertNotSame(firstSamples[index].image, sample.image)
            } else {
                assertSame(firstSamples[index].image, sample.image)
            }
        }
        assertEquals(expectedIds, source.requests)
        session.close()
    }

    @Test
    fun lodBudgetSelectsCoarserTilesAndEmptyFineTilesUseFullCoarseToFineLayers() {
        val fine = TiledImageLevel(IntSize(8, 8), 1L)
        val coarse = TiledImageLevel(IntSize(8, 8), 2L)
        val fallbackImage = image(coarse.tilePixelSize, 0xFF336699.toInt())
        val fallbackSource =
            RecordingSource(LongRect(0L, 0L, 16L, 8L), listOf(fine, coarse)) { id ->
                if (id.level == 0) TiledImageTile.Empty else TiledImageTile.Ready(fallbackImage)
            }
        val fallbackSession = session(fallbackSource, PanZoomState(), IntSize(16, 8), policy(8))
        fallbackSession.attach()
        val fallbackSamples = samples(fallbackSession.frame(Constraints.fixed(16, 8)))

        assertEquals(
            listOf(TiledImageTileId(0, 0L, 0L), TiledImageTileId(0, 1L, 0L), TiledImageTileId(1, 0L, 0L)),
            fallbackSource.requests,
        )
        assertEquals(1, fallbackSamples.size)
        assertSame(fallbackImage, fallbackSamples.single().image)
        assertEquals(FloatRect(0.0f, 0.0f, 8.0f, 8.0f), fallbackSamples.single().source)
        assertEquals(FloatRect(0.0f, 0.0f, 16.0f, 16.0f), fallbackSamples.single().destination)

        val fineImage = image(fine.tilePixelSize, 0xFFCC8844.toInt())
        fallbackSource.history(TiledImageTileId(0, 1L, 0L)).publish(TiledImageTile.Ready(fineImage))
        val refinedSamples = samples(fallbackSession.frame(Constraints.fixed(16, 8)))
        assertEquals(2, refinedSamples.size)
        assertSame(fallbackImage, refinedSamples[0].image)
        assertSame(fineImage, refinedSamples[1].image)
        assertEquals(
            listOf(FloatRect(0.0f, 0.0f, 8.0f, 8.0f), FloatRect(0.0f, 0.0f, 8.0f, 8.0f)),
            refinedSamples.map(DrawCommand.SampledImage::source),
        )
        assertEquals(
            listOf(FloatRect(0.0f, 0.0f, 16.0f, 16.0f), FloatRect(8.0f, 0.0f, 16.0f, 8.0f)),
            refinedSamples.map(DrawCommand.SampledImage::destination),
        )
        fallbackSession.close()

        val budgetSource =
            RecordingSource(LongRect(0L, 0L, 64L, 64L), listOf(fine, coarse)) { id ->
                TiledImageTile.Ready(image(if (id.level == 0) fine.tilePixelSize else coarse.tilePixelSize, color(id)))
            }
        val byteBudget = TiledImageCachePolicy(maxEntries = 100, maxBytes = 4_096L, overscanTiles = 0)
        val budgetSession = session(budgetSource, PanZoomState(), IntSize(64, 64), byteBudget)
        budgetSession.attach()
        assertEquals(16, samples(budgetSession.frame(Constraints.fixed(64, 64))).size)
        assertEquals(16, budgetSource.requests.size)
        assertTrue(budgetSource.requests.all { id -> id.level == 1 })
        budgetSession.close()

        val rejected =
            RecordingSource(LongRect(0L, 0L, 64L, 64L), listOf(fine, coarse)) { TiledImageTile.Empty }
        val rejectedSession = session(rejected, PanZoomState(), IntSize(64, 64), policy(3))
        rejectedSession.attach()
        assertThrows(IllegalStateException::class.java) { rejectedSession.frame(Constraints.fixed(64, 64)) }
        assertTrue(rejected.requests.isEmpty())
        rejectedSession.close()
    }

    @Test
    fun visibleFallbackTilesDoNotSpendTheSelectedLevelsOverscanMargin() {
        val fine = TiledImageLevel(IntSize(8, 8), 1L)
        val coarse = TiledImageLevel(IntSize(8, 8), 4L)
        val source =
            RecordingSource(LongRect(0L, 0L, 96L, 96L), listOf(fine, coarse)) { id ->
                val level = if (id.level == 0) fine else coarse
                TiledImageTile.Ready(image(level.tilePixelSize, color(id)))
            }
        val state = PanZoomState(initialCenter = DoubleOffset(44.0, 44.0), initialZoom = 12.0)
        val cachePolicy = TiledImageCachePolicy(maxEntries = 10, maxBytes = 2_560L, overscanTiles = 1)
        val session = session(source, state, IntSize(8, 8), cachePolicy)
        session.attach()

        val rendered = samples(session.frame(Constraints.fixed(8, 8)))

        assertEquals(2, rendered.size)
        assertEquals(10, source.requests.size)
        assertEquals(9, source.requests.count { id -> id.level == 0 })
        assertEquals(listOf(TiledImageTileId(1, 1L, 1L)), source.requests.filter { id -> id.level == 1 })
        assertTrue(source.maximumActiveSubscriptions <= cachePolicy.maxEntries)
        session.close()
        assertEquals(source.requests.size, source.histories.values.sumOf(TileFrames::closes))
    }

    @Test
    fun subscribeRaceWaitsForTheNextCutoffAndLaterInvalidTileCommitsNothing() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val bounds = LongRect(0L, 0L, 16L, 8L)
        val source =
            RecordingSource(bounds, listOf(level)) { id ->
                TiledImageTile.Ready(image(level.tilePixelSize, color(id)))
            }
        val firstId = TiledImageTileId(0, 0L, 0L)
        val secondId = TiledImageTileId(0, 1L, 0L)
        val raced = image(level.tilePixelSize, 0xFF010203.toInt())
        source.beforeSubscribe = { id, history ->
            if (id == firstId) history.publish(TiledImageTile.Ready(raced))
        }
        val session = session(source, PanZoomState(), IntSize(16, 8), policy(4))
        session.attach()

        val initial = samples(session.frame(Constraints.fixed(16, 8)))
        assertTrue(initial[0].image !== raced)
        val afterRaceFrame = session.frame(Constraints.fixed(16, 8))
        val afterRace = samples(afterRaceFrame)
        assertSame(raced, afterRace[0].image)

        val firstNew = image(level.tilePixelSize, 0xFF112233.toInt())
        source.history(firstId).publish(TiledImageTile.Ready(firstNew))
        source.history(secondId).publish(TiledImageTile.Ready(image(IntSize(1, 1), 0xFF445566.toInt())))
        val previousFrame = afterRaceFrame
        assertSame(raced, samples(previousFrame)[0].image)
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                session.frame(Constraints.fixed(16, 8), FrameTime(1L))
            }
        assertEquals("Ready tiled image dimensions must match their level.", failure.message)
        assertSame(raced, samples(previousFrame)[0].image)
        assertTrue(samples(previousFrame)[0].image !== firstNew)
        assertEquals(1, source.history(firstId).closes)
        assertEquals(1, source.history(secondId).closes)
        session.close()
    }

    @Test
    fun workerCallbacksAfterTheCutoffWaitForTheNextFrameAndKeepOnlyTheLatestRevision() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val initialImage = image(level.tilePixelSize, 0xFF010101.toInt())
        val intermediateImage = image(level.tilePixelSize, 0xFF020202.toInt())
        val latestImage = image(level.tilePixelSize, 0xFF030303.toInt())
        val source = RecordingSource(LongRect(0L, 0L, 8L, 8L), listOf(level)) { TiledImageTile.Ready(initialImage) }
        val state = PanZoomState()
        val rebuild = LocalHolder<Int>()
        val afterCutoff = CountDownLatch(1)
        val callbacksComplete = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val session =
            UiSession(TestOwnerDispatcher()) {
                if (rebuild.value == 1) {
                    afterCutoff.countDown()
                    check(callbacksComplete.await(5, TimeUnit.SECONDS)) { "Tile callbacks did not complete during the cutoff transaction." }
                }
                evaluateComponentTree {
                    TiledImage(source, state, IntSize(8, 8), cachePolicy = policy(1))
                }
            }
        rebuild.delegate = session.state(0)
        try {
            session.attach()
            val initialFrame = session.frame(Constraints.fixed(8, 8))
            val history = source.histories.values.single()
            val worker =
                executor.submit {
                    check(afterCutoff.await(5, TimeUnit.SECONDS)) { "Frame cutoff did not release the tile publisher." }
                    history.publish(TiledImageTile.Ready(intermediateImage))
                    history.publish(TiledImageTile.Ready(latestImage))
                    callbacksComplete.countDown()
                }

            rebuild.value = 1
            val cutoffFrame = session.frame(Constraints.fixed(8, 8))
            worker.get(5, TimeUnit.SECONDS)

            assertSame(initialImage, samples(initialFrame).single().image)
            assertSame(initialImage, samples(cutoffFrame).single().image)
            val committed = session.frame(Constraints.fixed(8, 8))
            assertSame(latestImage, samples(committed).single().image)
            assertTrue(samples(committed).single().image !== intermediateImage)
            assertSame(committed, session.frame(Constraints.fixed(8, 8)))
        } finally {
            afterCutoff.countDown()
            callbacksComplete.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            session.close()
        }
    }

    @Test
    @Suppress("LongMethod") // One controlled cutoff and reattachment prove revisioned overlay placement without disturbing tile presentation.
    fun revisionedOverlayPositionsCommitAtCutoffAndNeverReplaceStableTileImages() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val tileImage = image(level.tilePixelSize, 0xFF112233.toInt())
        val source = RecordingSource(LongRect(0L, 0L, 8L, 8L), listOf(level)) { TiledImageTile.Ready(tileImage) }
        val positions = PositionFrames(DoubleOffset(1.0, 1.0))
        val navigation = PanZoomState()
        val probe = TestProbe()
        val rebuild = LocalHolder<Int>()
        val afterCutoff = CountDownLatch(1)
        val callbackComplete = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val session =
            UiSession(TestOwnerDispatcher()) {
                if (rebuild.value == 1) {
                    afterCutoff.countDown()
                    check(callbackComplete.await(5, TimeUnit.SECONDS)) { "Overlay callback did not complete during the cutoff transaction." }
                }
                evaluateComponentTree {
                    TiledImage(source, navigation, IntSize(8, 8), cachePolicy = policy(1)) {
                        element(
                            probe.element(
                                TestProbe.ProbeId("revisioned-marker"),
                                modifier = Modifier.Empty.atContentPosition(positions),
                            ),
                        )
                    }
                }
            }
        rebuild.delegate = session.state(0)
        try {
            session.attach()
            val initial = session.frame(Constraints.fixed(8, 8))
            val initialTile = samples(initial).single()
            val initialMarker = initial.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single()
            val worker =
                executor.submit {
                    check(afterCutoff.await(5, TimeUnit.SECONDS)) { "Frame cutoff did not release the overlay publisher." }
                    positions.publish(DoubleOffset(5.0, 5.0))
                    callbackComplete.countDown()
                }

            rebuild.value = 1
            val cutoff = session.frame(Constraints.fixed(8, 8))
            worker.get(5, TimeUnit.SECONDS)

            assertEquals(initialMarker, cutoff.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single())
            val committed = session.frame(Constraints.fixed(8, 8))
            val committedTile = samples(committed).single()
            assertTrue(initialMarker != committed.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single())
            assertSame(initialTile.image, committedTile.image)
            assertEquals(initialTile.destination, committedTile.destination)

            session.detach()
            assertEquals(1, positions.closes)
            positions.publish(DoubleOffset(7.0, 7.0))
            session.attach()
            val reattached = session.frame(Constraints.fixed(8, 8))
            assertTrue(
                committed.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single() !=
                    reattached.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single(),
            )
            assertSame(tileImage, samples(reattached).single().image)
        } finally {
            afterCutoff.countDown()
            callbackComplete.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            session.close()
        }
        assertEquals(2, positions.subscriptions)
        assertEquals(2, positions.closes)
    }

    @Test
    fun revisionedOverlayReplacementClosesBeforeSubscribeAndDefersItsReturnRace() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val tileImage = image(level.tilePixelSize, 0xFF112233.toInt())
        val source = RecordingSource(LongRect(0L, 0L, 8L, 8L), listOf(level)) { TiledImageTile.Ready(tileImage) }
        val events = ArrayList<String>()
        val first = PositionFrames(DoubleOffset(1.0, 1.0), onClose = { events.add("first-close") })
        val second = PositionFrames(DoubleOffset(3.0, 3.0), onSubscribe = { events.add("second-open") })
        second.duringSubscribe = { second.publish(DoubleOffset(6.0, 6.0)) }
        val holder = LocalHolder<StateSource<DoubleOffset>>()
        val navigation = PanZoomState()
        val probe = TestProbe()
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    TiledImage(source, navigation, IntSize(8, 8), cachePolicy = policy(1)) {
                        element(
                            probe.element(
                                TestProbe.ProbeId("replacement-marker"),
                                modifier = Modifier.Empty.atContentPosition(holder.value),
                            ),
                        )
                    }
                }
            }
        holder.delegate = session.state(first)
        session.attach()
        val initial = session.frame(Constraints.fixed(8, 8))
        holder.value = second

        val replacement = session.frame(Constraints.fixed(8, 8))
        val afterReturnRace = session.frame(Constraints.fixed(8, 8))

        val initialMarker = initial.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single()
        val replacementMarker = replacement.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single()
        val laterMarker = afterReturnRace.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single()
        assertTrue(initialMarker != replacementMarker)
        assertTrue(replacementMarker != laterMarker)
        assertEquals(listOf("first-close", "second-open"), events)
        assertEquals(1, first.subscriptions)
        assertEquals(1, first.closes)
        assertEquals(1, second.subscriptions)
        assertEquals(0, second.closes)
        assertSame(tileImage, samples(replacement).single().image)
        assertSame(tileImage, samples(afterReturnRace).single().image)

        session.close()
        assertEquals(1, second.closes)
    }

    @Test
    @Suppress("LongMethod") // Two controlled worker deliveries cover both resumable detach and terminal close races.
    fun racedWorkerCallbacksCannotReviveTileEntriesClosedByDetachOrClose() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val initialImage = image(level.tilePixelSize, 0xFF111111.toInt())
        val detachedImage = image(level.tilePixelSize, 0xFF222222.toInt())
        val terminalImage = image(level.tilePixelSize, 0xFF333333.toInt())
        val source = RecordingSource(LongRect(0L, 0L, 8L, 8L), listOf(level)) { TiledImageTile.Ready(initialImage) }
        val session = session(source, PanZoomState(), IntSize(8, 8), policy(1))
        val executor = Executors.newSingleThreadExecutor()
        val detachedCallbackStarted = CountDownLatch(1)
        val releaseDetachedCallback = CountDownLatch(1)
        val terminalCallbackStarted = CountDownLatch(1)
        val releaseTerminalCallback = CountDownLatch(1)
        try {
            session.attach()
            session.frame(Constraints.fixed(8, 8))
            val history = source.histories.values.single()
            val detachedWorker =
                executor.submit {
                    history.publishAfterRelease(TiledImageTile.Ready(detachedImage), detachedCallbackStarted, releaseDetachedCallback)
                }
            assertTrue(detachedCallbackStarted.await(5, TimeUnit.SECONDS))

            session.detach()
            assertEquals(0, source.activeSubscriptions)
            assertEquals(1, history.closes)
            session.attach()
            val reattached = session.frame(Constraints.fixed(8, 8))
            assertSame(detachedImage, samples(reattached).single().image)
            assertEquals(2, history.subscriptions)
            releaseDetachedCallback.countDown()
            detachedWorker.get(5, TimeUnit.SECONDS)
            assertSame(reattached, session.frame(Constraints.fixed(8, 8)))

            val terminalWorker =
                executor.submit {
                    history.publishAfterRelease(TiledImageTile.Ready(terminalImage), terminalCallbackStarted, releaseTerminalCallback)
                }
            assertTrue(terminalCallbackStarted.await(5, TimeUnit.SECONDS))
            session.close()
            assertEquals(0, source.activeSubscriptions)
            assertEquals(history.subscriptions, history.closes)
            releaseTerminalCallback.countDown()
            terminalWorker.get(5, TimeUnit.SECONDS)
            assertEquals(0, source.activeSubscriptions)
            assertEquals(history.subscriptions, history.closes)
        } finally {
            releaseDetachedCallback.countDown()
            releaseTerminalCallback.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            session.close()
        }
    }

    @Test
    @Suppress("LongMethod") // One reconciliation transaction verifies geometry, observer, and bounded working-set replacement together.
    fun viewportFitStateAndPolicyReplacementReuseIdentityAndCloseBeforeOpening() {
        val fine = TiledImageLevel(IntSize(8, 8), 1L)
        val coarse = TiledImageLevel(IntSize(8, 8), 2L)
        val source =
            RecordingSource(LongRect(0L, 0L, 64L, 16L), listOf(fine, coarse)) { id ->
                val level = if (id.level == 0) fine else coarse
                TiledImageTile.Ready(image(level.tilePixelSize, color(id)))
            }
        val oldState = PanZoomState()
        val newState = PanZoomState(initialCenter = DoubleOffset(40.0, 8.0))
        val size = LocalHolder<IntSize>()
        val fit = LocalHolder<PanZoomFit>()
        val state = LocalHolder<PanZoomState>()
        val cachePolicy = LocalHolder<TiledImageCachePolicy>()
        val initialPolicy = policy(8)
        val reducedPolicy = policy(5)
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    TiledImage(source, state.value, size.value, fit = fit.value, cachePolicy = cachePolicy.value)
                }
            }
        size.delegate = session.state(IntSize(16, 8))
        fit.delegate = session.state(PanZoomFit.Contain)
        state.delegate = session.state(oldState)
        cachePolicy.delegate = session.state(initialPolicy)
        session.attach()
        val initialSamples = samples(session.frame(Constraints.fixed(16, 8)))
        val retainedId = TiledImageTileId(1, 2L, 0L)
        val transactionStart = source.events.size

        size.value = IntSize(16, 16)
        fit.value = PanZoomFit.Cover
        state.value = newState
        cachePolicy.value = reducedPolicy
        val replaced = session.frame(Constraints.fixed(16, 16))

        val transactionEvents = source.events.drop(transactionStart)
        val closes = transactionEvents.filterIsInstance<SourceEvent.Close>()
        val opens = transactionEvents.filterIsInstance<SourceEvent.Open>()
        assertEquals(
            setOf(TiledImageTileId(1, 0L, 0L), TiledImageTileId(1, 1L, 0L), TiledImageTileId(1, 3L, 0L)),
            closes.map(SourceEvent.Close::id).toSet(),
        )
        assertEquals(
            setOf(
                TiledImageTileId(0, 4L, 0L),
                TiledImageTileId(0, 5L, 0L),
                TiledImageTileId(0, 4L, 1L),
                TiledImageTileId(0, 5L, 1L),
            ),
            opens.map(SourceEvent.Open::id).toSet(),
        )
        assertTrue(transactionEvents.indexOfLast { event -> event is SourceEvent.Close } < transactionEvents.indexOfFirst { event -> event is SourceEvent.Open })
        assertSame(initialSamples[2].image, samples(replaced).first().image)
        assertEquals(1, source.history(retainedId).subscriptions)
        assertEquals(0, source.history(retainedId).closes)
        assertEquals(reducedPolicy.maxEntries, source.activeSubscriptions)
        assertTrue(source.maximumActiveSubscriptions <= reducedPolicy.maxEntries)

        val settled = session.frame(Constraints.fixed(16, 16))
        assertSame(settled, session.frame(Constraints.fixed(16, 16)))
        oldState.zoomBy(2.0)
        assertSame(settled, session.frame(Constraints.fixed(16, 16)))
        session.close()
        assertEquals(source.requests.size, source.histories.values.sumOf(TileFrames::closes))
    }

    @Test
    @Suppress("LongMethod") // One continuous working-set transaction proves identity reuse, one-boundary churn, and balanced detach cleanup.
    fun panAndOverlayMovementReuseTilesWhileBoundaryCrossingEvictsOnlyExitedOverscan() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val source =
            RecordingSource(LongRect(-32L, -32L, 32L, 32L), listOf(level)) { id ->
                TiledImageTile.Ready(image(level.tilePixelSize, color(id)))
            }
        val state = PanZoomState(initialCenter = DoubleOffset(1.0, 1.0), initialZoom = 4.0)
        val workingSetPolicy = TiledImageCachePolicy(maxEntries = 25, maxBytes = 8_388_608L, overscanTiles = 1)
        val markerPosition = LocalHolder<DoubleOffset>()
        val probe = TestProbe()
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    TiledImage(source, state, IntSize(16, 16), cachePolicy = workingSetPolicy) {
                        element(
                            probe.element(
                                TestProbe.ProbeId("marker"),
                                modifier = Modifier.Empty.atContentPosition(markerPosition.value),
                            ),
                        )
                    }
                }
            }
        markerPosition.delegate = session.state(DoubleOffset.Zero)
        session.attach()
        val firstSamples = samples(session.frame(Constraints.fixed(16, 16)))
        val requestCount = source.requests.size
        val subscriptions = source.histories.values.sumOf(TileFrames::subscriptions)

        state.panBy(DoubleOffset(1.0, 1.0))
        val pannedSamples = samples(session.frame(Constraints.fixed(16, 16)))
        assertEquals(requestCount, source.requests.size)
        assertEquals(subscriptions, source.histories.values.sumOf(TileFrames::subscriptions))
        assertEquals(firstSamples.size, pannedSamples.size)
        firstSamples.zip(pannedSamples).forEach { (before, after) -> assertSame(before.image, after.image) }
        assertTrue(firstSamples.map(DrawCommand.SampledImage::destination) != pannedSamples.map(DrawCommand.SampledImage::destination))

        markerPosition.value = DoubleOffset(2.0, 2.0)
        val overlaySamples = samples(session.frame(Constraints.fixed(16, 16)))
        assertEquals(requestCount, source.requests.size)
        pannedSamples.zip(overlaySamples).forEach { (before, after) ->
            assertSame(before.image, after.image)
            assertEquals(before.destination, after.destination)
        }

        val retainedBeforeBoundary = source.histories.keys.toSet()
        state.panBy(DoubleOffset(7.0, 0.0))
        session.frame(Constraints.fixed(16, 16))
        val exited = retainedBeforeBoundary.filterTo(LinkedHashSet()) { id -> id.column == -2L }
        val retained = retainedBeforeBoundary - exited
        val entered = source.histories.keys - retainedBeforeBoundary
        assertEquals(5, exited.size)
        assertEquals(5, entered.size)
        assertTrue(entered.all { id -> id.column == 3L })
        exited.forEach { id ->
            assertEquals(1, source.history(id).subscriptions)
            assertEquals(1, source.history(id).closes)
        }
        retained.forEach { id ->
            assertEquals(1, source.history(id).subscriptions)
            assertEquals(0, source.history(id).closes)
        }
        entered.forEach { id ->
            assertEquals(1, source.history(id).subscriptions)
            assertEquals(0, source.history(id).closes)
        }
        assertEquals(workingSetPolicy.maxEntries, source.activeSubscriptions)
        assertTrue(source.maximumActiveSubscriptions <= workingSetPolicy.maxEntries)

        val requestsBeforeDetach = source.requests.size
        session.detach()
        assertEquals(requestsBeforeDetach, source.histories.values.sumOf(TileFrames::closes))
        session.attach()
        session.frame(Constraints.fixed(16, 16))
        assertEquals(requestsBeforeDetach + workingSetPolicy.maxEntries, source.requests.size)
        session.close()
        assertEquals(source.requests.size, source.histories.values.sumOf(TileFrames::closes))
    }

    @Test
    fun sourceReplacementClearsBindingsAndSameIdentityGeometryChangesAreRejected() {
        val level = TiledImageLevel(IntSize(8, 8), 1L)
        val first = RecordingSource(LongRect(0L, 0L, 8L, 8L), listOf(level)) { TiledImageTile.Ready(image(level.tilePixelSize, 1)) }
        val second = RecordingSource(LongRect(0L, 0L, 8L, 8L), listOf(level)) { TiledImageTile.Ready(image(level.tilePixelSize, 2)) }
        val sourceHolder = LocalHolder<TiledImageSource>()
        val rebuild = LocalHolder<Int>()
        val state = PanZoomState()
        val session =
            UiSession(TestOwnerDispatcher()) {
                rebuild.value
                evaluateComponentTree { TiledImage(sourceHolder.value, state, IntSize(8, 8), cachePolicy = policy(2)) }
            }
        sourceHolder.delegate = session.state(first)
        rebuild.delegate = session.state(0)
        session.attach()
        val firstFrame = session.frame(Constraints.fixed(8, 8))
        sourceHolder.value = second
        val secondFrame = session.frame(Constraints.fixed(8, 8))
        assertNotSame(samples(firstFrame).single().image, samples(secondFrame).single().image)
        assertEquals(1, first.histories.values.sumOf(TileFrames::closes))

        second.bounds = LongRect(0L, 0L, 16L, 8L)
        rebuild.value = 1
        val failure = assertThrows(IllegalStateException::class.java) { session.frame(Constraints.fixed(8, 8)) }
        assertEquals("Tiled image geometry cannot change without replacing its source identity.", failure.message)
        assertEquals(1, second.requests.size)
        assertEquals(1, second.histories.values.sumOf(TileFrames::closes))
        session.close()
    }

    private fun session(
        source: TiledImageSource,
        state: PanZoomState,
        size: IntSize,
        cachePolicy: TiledImageCachePolicy,
    ): UiSession =
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree {
                TiledImage(source, state, size, fit = PanZoomFit.Contain, cachePolicy = cachePolicy)
            }
        }

    private fun samples(frame: UiFrame): List<DrawCommand.SampledImage> = frame.drawCommands.filterIsInstance<DrawCommand.SampledImage>()

    private fun policy(maxEntries: Int): TiledImageCachePolicy = TiledImageCachePolicy(maxEntries = maxEntries, maxBytes = 8_388_608L, overscanTiles = 0)

    private fun image(
        size: IntSize,
        color: Int,
    ): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })

    private fun color(id: TiledImageTileId): Int =
        0xFF000000.toInt() or
            ((id.level + 1) shl 20) or
            ((id.column.toInt() and 0xFF) shl 8) or
            (id.row.toInt() and 0xFF)

    private class RecordingSource(
        override var bounds: LongRect,
        override var levels: List<TiledImageLevel>,
        private val initial: (TiledImageTileId) -> TiledImageTile,
    ) : TiledImageSource {
        val requests: MutableList<TiledImageTileId> = ArrayList()
        val histories: MutableMap<TiledImageTileId, TileFrames> = LinkedHashMap()
        val events: MutableList<SourceEvent> = ArrayList()
        var beforeSubscribe: ((TiledImageTileId, TileFrames) -> Unit)? = null
        var activeSubscriptions: Int = 0
        var maximumActiveSubscriptions: Int = 0

        override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
            require(id.level in levels.indices)
            requests.add(id)
            events.add(SourceEvent.Open(id))
            val history =
                histories.getOrPut(id) {
                    TileFrames(
                        initial(id),
                        onSubscribe = {
                            activeSubscriptions += 1
                            maximumActiveSubscriptions = maxOf(maximumActiveSubscriptions, activeSubscriptions)
                        },
                        onClose = {
                            activeSubscriptions -= 1
                            events.add(SourceEvent.Close(id))
                        },
                    )
                }
            history.beforeSubscribe = { beforeSubscribe?.invoke(id, history) }
            return history
        }

        fun history(id: TiledImageTileId): TileFrames = checkNotNull(histories[id])
    }

    private class TileFrames(
        initial: TiledImageTile,
        private val onSubscribe: () -> Unit,
        private val onClose: () -> Unit,
    ) : StateSource<TiledImageTile> {
        private val monitor = Any()
        private var snapshot = StateSnapshot(StateRevision(0L), initial)
        private val observers: MutableList<(StateSnapshot<TiledImageTile>) -> Unit> = ArrayList()
        var subscriptions: Int = 0
        var closes: Int = 0
        var beforeSubscribe: (() -> Unit)? = null

        override fun subscribe(observer: (StateSnapshot<TiledImageTile>) -> Unit): StateSubscription<TiledImageTile> {
            val initial =
                synchronized(monitor) {
                    observers.add(observer)
                    snapshot
                }
            subscriptions += 1
            onSubscribe()
            beforeSubscribe?.invoke()
            return StateSubscription(initial) {
                synchronized(monitor) { observers.remove(observer) }
                closes += 1
                onClose()
            }
        }

        fun publish(tile: TiledImageTile) {
            val notification = notification(tile)
            notification.observers.forEach { observer -> observer(notification.snapshot) }
        }

        fun publishAfterRelease(
            tile: TiledImageTile,
            callbackStarted: CountDownLatch,
            releaseCallback: CountDownLatch,
        ) {
            val notification = notification(tile)
            callbackStarted.countDown()
            check(releaseCallback.await(5, TimeUnit.SECONDS)) { "Raced tile callback was not released." }
            notification.observers.forEach { observer -> observer(notification.snapshot) }
        }

        private fun notification(tile: TiledImageTile): Notification =
            synchronized(monitor) {
                snapshot = StateSnapshot(StateRevision(Math.incrementExact(snapshot.revision.value)), tile)
                Notification(snapshot, observers.toList())
            }

        private data class Notification(
            val snapshot: StateSnapshot<TiledImageTile>,
            val observers: List<(StateSnapshot<TiledImageTile>) -> Unit>,
        )
    }

    private class PositionFrames(
        initial: DoubleOffset,
        private val onSubscribe: () -> Unit = {},
        private val onClose: () -> Unit = {},
    ) : StateSource<DoubleOffset> {
        private val monitor = Any()
        private var snapshot = StateSnapshot(StateRevision(0L), initial)
        private val observers: MutableList<(StateSnapshot<DoubleOffset>) -> Unit> = ArrayList()
        var subscriptions: Int = 0
        var closes: Int = 0
        var duringSubscribe: (() -> Unit)? = null

        override fun subscribe(observer: (StateSnapshot<DoubleOffset>) -> Unit): StateSubscription<DoubleOffset> {
            val initialSnapshot =
                synchronized(monitor) {
                    observers.add(observer)
                    snapshot
                }
            subscriptions += 1
            onSubscribe()
            duringSubscribe?.invoke()
            return StateSubscription(initialSnapshot) {
                synchronized(monitor) { observers.remove(observer) }
                closes += 1
                onClose()
            }
        }

        fun publish(position: DoubleOffset) {
            val notification =
                synchronized(monitor) {
                    snapshot = StateSnapshot(StateRevision(Math.incrementExact(snapshot.revision.value)), position)
                    snapshot to observers.toList()
                }
            notification.second.forEach { observer -> observer(notification.first) }
        }
    }

    private sealed interface SourceEvent {
        data class Open(
            val id: TiledImageTileId,
        ) : SourceEvent

        data class Close(
            val id: TiledImageTileId,
        ) : SourceEvent
    }

    private class LocalHolder<T> {
        lateinit var delegate: ReadWriteProperty<Any?, T>

        var value: T
            get() = delegate.getValue(this, LocalHolder<T>::value)
            set(next) = delegate.setValue(this, LocalHolder<T>::value, next)
    }
}
