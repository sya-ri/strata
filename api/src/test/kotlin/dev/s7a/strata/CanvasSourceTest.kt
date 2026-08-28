package dev.s7a.strata

import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasId
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Verifies immutable CPU canvas input, lazy subscriptions, revision races, bounded snapshots, and terminal release.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class CanvasSourceTest {
    @Test
    fun staticImageNeverRetainsAMutablePixelArrayAndPaintsTheWholeImage() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val image = createDrawImage(IntSize(2, 1), pixels)
        val binding = canvasSource(image).open(CanvasId(1))
        pixels.fill(0)
        image.copyArgb().fill(0)
        val scope = Scope(IntSize(8, 6))
        try {
            binding.paint(scope)
            assertSame(image, scope.image)
            assertEquals(0xFFFF0000.toInt(), scope.image?.argbAt(0, 0))
            assertEquals(0xFF0000FF.toInt(), scope.image?.argbAt(1, 0))
            assertEquals(IntRect(0, 0, 2, 1), scope.source)
            assertEquals(IntRect(0, 0, 8, 6), scope.destination)
        } finally {
            binding.close()
        }
    }

    @Test
    fun imageObservationStartsOnlyWhenTheSourceIsOpened() {
        val initial = snapshot(1)
        var subscriptions = 0
        var closes = 0
        val source =
            canvasSource(
                StateSource {
                    subscriptions += 1
                    StateSubscription(initial) { closes += 1 }
                },
            )
        assertEquals(0, subscriptions)

        val first = source.open(CanvasId(1))
        val second = source.open(CanvasId(2))
        first.close()
        first.close()
        assertEquals(2, subscriptions)
        assertEquals(1, closes)
        second.close()
        assertEquals(2, closes)
    }

    @Test
    fun callbackBeforeSubscribeReturnsRemainsPendingBehindTheInitialObservation() {
        val initial = snapshot(1)
        val latest = snapshot(2)
        val source =
            StateSource<DrawImage> { observer ->
                observer(latest)
                StateSubscription(initial) {}
            }
        val binding = canvasSource(source).open(CanvasId(1))
        try {
            assertSame(initial.value, paintedImage(binding))
            binding.captureFrame()
            assertTrue(binding.commitFrame())
            assertSame(latest.value, paintedImage(binding))
            binding.captureFrame()
            assertFalse(binding.commitFrame())
        } finally {
            binding.close()
        }
    }

    @Test
    fun arbitraryThreadCallbacksOnlyReplaceTheLatestPendingRevision() {
        lateinit var observer: (StateSnapshot<DrawImage>) -> Unit
        val binding =
            canvasSource(
                StateSource { callback ->
                    observer = callback
                    StateSubscription(snapshot(1)) {}
                },
            ).open(CanvasId(1))
        try {
            val initialImage = paintedImage(binding)
            val task =
                FutureTask<Unit> {
                    (2..500).forEach { revision -> observer(snapshot(revision.toLong())) }
                }
            Thread(task).start()
            task.get(5, TimeUnit.SECONDS)
            assertSame(initialImage, paintedImage(binding))
            assertEquals(listOf(1L, 500L), retainedSnapshots(binding).map { it.revision.value }.sorted())

            binding.captureFrame()
            assertTrue(binding.commitFrame())
            observer(snapshot(2))
            observer(StateSnapshot(StateRevision(500), snapshot(800).value))
            binding.captureFrame()
            assertFalse(binding.commitFrame())
            assertEquals(snapshot(500).value, paintedImage(binding))
            assertEquals(listOf(500L), retainedSnapshots(binding).map { it.revision.value })
        } finally {
            binding.close()
        }
        observer(snapshot(600))
        assertTrue(retainedSnapshots(binding).isEmpty())
    }

    @Test
    fun cutoffRetainsOnlyOneTemporaryObservationAndDoesNotConsumeALaterCallback() {
        lateinit var observer: (StateSnapshot<DrawImage>) -> Unit
        val binding =
            canvasSource(
                StateSource { callback ->
                    observer = callback
                    StateSubscription(snapshot(1)) {}
                },
            ).open(CanvasId(1))
        try {
            observer(snapshot(2))
            binding.captureFrame()
            observer(snapshot(3))
            assertEquals(listOf(1L, 2L, 3L), retainedSnapshots(binding).map { it.revision.value }.sorted())
            assertTrue(binding.commitFrame())
            assertEquals(snapshot(2).value, paintedImage(binding))
            assertEquals(listOf(2L, 3L), retainedSnapshots(binding).map { it.revision.value }.sorted())
            binding.captureFrame()
            assertTrue(binding.commitFrame())
            assertEquals(snapshot(3).value, paintedImage(binding))
            assertEquals(listOf(3L), retainedSnapshots(binding).map { it.revision.value })
        } finally {
            binding.close()
        }
    }

    @Test
    fun invalidPublishedImagesFailOnTheOwnerThreadAndStillCloseTheSource() {
        lateinit var observer: (StateSnapshot<DrawImage>) -> Unit
        var closes = 0
        val binding =
            canvasSource(
                StateSource { callback ->
                    observer = callback
                    StateSubscription(snapshot(1)) { closes += 1 }
                },
            ).open(CanvasId(1))
        observer(StateSnapshot(StateRevision(2), createDrawImage(IntSize.Zero, intArrayOf())))
        binding.captureFrame()
        assertThrows(IllegalArgumentException::class.java) { binding.commitFrame() }
        binding.close()
        assertEquals(1, closes)
        assertTrue(retainedSnapshots(binding).isEmpty())
    }

    @Test
    fun invalidInitialImageClosesTheAcquiredSubscriptionAndPreservesPrimaryFailure() {
        val cleanupFailure = IllegalStateException("subscription cleanup")
        var closes = 0
        val empty = createDrawImage(IntSize.Zero, intArrayOf())
        val source =
            canvasSource(
                StateSource {
                    StateSubscription(StateSnapshot(StateRevision(0), empty)) {
                        closes += 1
                        throw cleanupFailure
                    }
                },
            )
        val failure = assertThrows(IllegalArgumentException::class.java) { source.open(CanvasId(1)) }
        assertEquals(listOf(cleanupFailure), failure.suppressed.toList())
        assertEquals(1, closes)
        assertThrows(IllegalArgumentException::class.java) { canvasSource(empty) }
    }

    private fun snapshot(revision: Long): StateSnapshot<DrawImage> =
        StateSnapshot(
            StateRevision(revision),
            createDrawImage(IntSize(1, 1), intArrayOf(0xFF000000.toInt() or revision.toInt())),
        )

    private fun paintedImage(binding: CanvasBinding): DrawImage {
        val scope = Scope(IntSize(1, 1))
        binding.paint(scope)
        return checkNotNull(scope.image)
    }

    private fun retainedSnapshots(binding: CanvasBinding): List<StateSnapshot<*>> =
        binding.javaClass.declaredFields.mapNotNull { field ->
            field.isAccessible = true
            field.get(binding) as? StateSnapshot<*>
        }

    private class Scope(
        override val size: IntSize,
    ) : PaintScope {
        var image: DrawImage? = null
        var source: IntRect? = null
        var destination: IntRect? = null

        override fun fillRectangle(
            localBounds: IntRect,
            color: ArgbColor,
        ): Unit = error("CPU canvases emit only image commands.")

        override fun blitImage(
            image: DrawImage,
            source: IntRect,
            localDestination: IntRect,
        ) {
            this.image = image
            this.source = source
            destination = localDestination
        }

        override fun drawPlatform(
            command: PlatformDrawCommand,
            localBounds: IntRect,
        ): Unit = error("CPU canvases cannot emit native commands.")
    }
}
