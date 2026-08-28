package dev.s7a.strata.runtime

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasId
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
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
import kotlin.properties.ReadWriteProperty

/**
 * Verifies canvas integration with retained caches, whole-frame source cutoffs, session suspension, and failures.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class CanvasSessionTest {
    @Test
    fun untimedFramesDrainImagesWithoutRebuildingContentOrRemeasuring() {
        val frames = Frames(image(1))
        val source = canvasSource(frames)
        val probe = TestProbe()
        var contentCalls = 0
        val session =
            UiSession(TestOwnerDispatcher()) {
                contentCalls += 1
                probe.root(listOf(evaluateComponentTree { Canvas(source, IntSize(2, 1)) }))
            }
        session.attach()
        val first = session.frame(Constraints.fixed(2, 1))
        val measureCalls = probe.measureCalls
        assertSame(first, session.frame(Constraints.fixed(2, 1)))
        val resizedImage = createDrawImage(IntSize(3, 2), IntArray(6) { 0xFF336699.toInt() })
        frames.publish(resizedImage)

        val next = session.frame(Constraints.fixed(2, 1))
        assertNotSame(first, next)
        assertEquals(1, contentCalls)
        assertEquals(measureCalls, probe.measureCalls)
        val blit = next.drawCommands.filterIsInstance<DrawCommand.BlitImage>().single()
        assertSame(resizedImage, blit.image)
        assertEquals(IntRect(0, 0, 3, 2), blit.source)
        assertEquals(IntRect(0, 0, 2, 1), blit.destination)
        assertSame(next, session.frame(Constraints.fixed(2, 1)))
        assertSame(next, session.frame(Constraints.fixed(2, 1), FrameTime(100)))
        frames.publish(resizedImage)
        assertSame(next, session.frame(Constraints.fixed(2, 1)))
        val equalReplacement = createDrawImage(resizedImage.size, resizedImage.copyArgb())
        frames.publish(equalReplacement)
        assertSame(equalReplacement, images(session.frame(Constraints.fixed(2, 1))).single())
        session.close()
    }

    @Test
    fun aSubscribeReturnRaceIsDrainedByTheFirstUntimedFrame() {
        val frames = Frames(image(1))
        val latest = image(2)
        frames.duringSubscribe = { frames.publish(latest) }
        val source = canvasSource(frames)
        val session = session(source)
        assertEquals(0, frames.subscriptions)
        session.attach()
        assertEquals(listOf(latest), images(session.frame(Constraints.fixed(1, 1))))
        session.close()
    }

    @Test
    fun everyCanvasCapturesBeforeAnyCanvasCommits() {
        val initial = image(1)
        val cutoff = image(2)
        val later = image(3)
        val frames = Frames(initial)
        val source = canvasSource(frames)
        var publishDuringCommit = false
        val firstSource =
            CanvasSource { identity ->
                val binding = source.open(identity)
                object : CanvasBinding by binding {
                    override fun commitFrame(): Boolean {
                        if (publishDuringCommit) {
                            publishDuringCommit = false
                            frames.publish(later)
                        }
                        return binding.commitFrame()
                    }
                }
            }
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    Row {
                        Canvas(firstSource, IntSize(1, 1))
                        Canvas(source, IntSize(1, 1))
                    }
                }
            }
        session.attach()
        session.frame(Constraints.fixed(2, 1))
        frames.publish(cutoff)
        publishDuringCommit = true

        assertEquals(listOf(cutoff, cutoff), images(session.frame(Constraints.fixed(2, 1))))
        assertEquals(listOf(later, later), images(session.frame(Constraints.fixed(2, 1))))
        session.close()
    }

    @Test
    fun sessionBindingEqualityCannotPublishIntoAnAlreadyCapturedCanvasFrame() {
        val frames = Frames(image(1))
        val source = canvasSource(frames)
        val cutoff = image(2)
        val later = image(3)
        lateinit var observer: (StateSnapshot<ComparisonValue>) -> Unit
        val session = session(source)
        session.bind(
            StateSource { callback ->
                observer = callback
                StateSubscription(StateSnapshot(StateRevision(1), ComparisonValue(1) { frames.publish(later) })) {}
            },
        )
        session.attach()
        session.frame(Constraints.fixed(1, 1))
        frames.publish(cutoff)
        observer(StateSnapshot(StateRevision(2), ComparisonValue(2)))

        assertEquals(listOf(cutoff), images(session.frame(Constraints.fixed(1, 1))))
        assertEquals(listOf(later), images(session.frame(Constraints.fixed(1, 1))))
        session.close()
    }

    @Test
    fun observationsPublishedByMeasurementWaitForTheNextFrame() {
        val initial = image(1)
        val latest = image(2)
        val frames = Frames(initial)
        val source = canvasSource(frames)
        val probe = TestProbe()
        val session =
            UiSession(TestOwnerDispatcher()) {
                probe.element(
                    TestProbe.ProbeId("parent"),
                    children = listOf(evaluateComponentTree { Canvas(source, IntSize(1, 1)) }),
                    onMeasure = { frames.publish(latest) },
                )
            }
        session.attach()
        assertEquals(listOf(initial), images(session.frame(Constraints.fixed(1, 1))))
        assertEquals(listOf(latest), images(session.frame(Constraints.fixed(1, 1))))
        session.close()
    }

    @Test
    fun sourceReplacementKeepsItsReturnRaceBehindTheCurrentFrameCutoff() {
        val first = canvasSource(image(1))
        val initial = image(2)
        val later = image(3)
        val frames = Frames(initial)
        frames.duringSubscribe = { frames.publish(later) }
        val holder = LocalHolder<CanvasSource>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree { Canvas(holder.value, IntSize(1, 1)) }
            }
        holder.delegate = session.state(first)
        session.attach()
        session.frame(Constraints.fixed(1, 1))
        holder.value = canvasSource(frames)

        assertEquals(listOf(initial), images(session.frame(Constraints.fixed(1, 1))))
        assertEquals(listOf(later), images(session.frame(Constraints.fixed(1, 1))))
        session.close()
    }

    @Test
    fun reattachmentAndSourceReplacementKeepNodeIdentityWhileReopeningBindings() {
        val first = TrackingSource(canvasSource(image(1)))
        val second = TrackingSource(canvasSource(image(2)))
        val holder = LocalHolder<CanvasSource>()
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree { Canvas(holder.value, IntSize(1, 1)) }
            }
        holder.delegate = session.state(first)
        session.attach()
        val old = session.frame(Constraints.fixed(1, 1))
        assertEquals(InputResult.Ignored, session.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)))
        assertTrue(old.semantics.isEmpty())
        session.detach()
        assertEquals(1, first.closes)
        session.attach()
        session.frame(Constraints.fixed(1, 1))
        holder.value = second
        session.frame(Constraints.fixed(1, 1))
        assertEquals(2, first.closes)
        assertEquals(listOf(first.identities.first(), first.identities.first()), first.identities)
        assertEquals(first.identities.first(), second.identities.single())
        session.close()
        assertEquals(1, second.closes)
        assertEquals(listOf(image(1)), images(old))
    }

    @Test
    fun detachmentBeforeTheFirstFrameStopsSourceCallbacksAndReopensTheLatestObservation() {
        val frames = Frames(image(1))
        val session = session(canvasSource(frames))
        session.attach()
        session.detach()
        assertEquals(1, frames.closes)
        assertEquals(0, frames.observers.size)
        val latest = image(2)
        frames.publish(latest)
        session.attach()
        assertEquals(2, frames.subscriptions)
        assertEquals(listOf(latest), images(session.frame(Constraints.fixed(1, 1))))
        session.close()
        assertEquals(2, frames.closes)
    }

    @Test
    fun detachAttemptsEveryBindingAndDoesNotRepeatFailedCleanupDuringTerminalClose() {
        val firstFailure = IllegalStateException("first subscription")
        val secondFailure = IllegalStateException("second subscription")
        val first = Frames(image(1), firstFailure)
        val second = Frames(image(2), secondFailure)
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    Row {
                        Canvas(canvasSource(first), IntSize(1, 1))
                        Canvas(canvasSource(second), IntSize(1, 1))
                    }
                }
            }
        session.attach()
        val failure = assertThrows(IllegalStateException::class.java) { session.detach() }
        assertSame(secondFailure, failure)
        assertEquals(listOf(firstFailure), failure.suppressed.toList())
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        session.close()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test
    fun aLaterSourceAcquisitionFailureClosesEarlierBindingsWithoutPainting() {
        val first = TrackingSource(canvasSource(image(1)))
        val expected = IllegalStateException("source acquisition")
        val failing = CanvasSource { throw expected }
        val session =
            UiSession(TestOwnerDispatcher()) {
                evaluateComponentTree {
                    Row {
                        Canvas(first, IntSize(1, 1))
                        Canvas(failing, IntSize(1, 1))
                    }
                }
            }
        assertSame(expected, assertThrows(IllegalStateException::class.java) { session.attach() })
        assertEquals(1, first.closes)
        session.close()
    }

    @Test
    fun invalidDestinationAndParentConstraintMismatchFailExplicitly() {
        val source = TrackingSource(canvasSource(image(1)))
        assertThrows(IllegalArgumentException::class.java) {
            evaluateComponentTree { Canvas(source, IntSize.Zero) }
        }
        assertTrue(source.identities.isEmpty())
        val session = session(source)
        session.attach()
        assertThrows(IllegalArgumentException::class.java) { session.frame(Constraints.fixed(2, 2)) }
        assertEquals(1, source.closes)
        session.close()
    }

    private fun session(source: CanvasSource): UiSession =
        UiSession(TestOwnerDispatcher()) {
            evaluateComponentTree { Canvas(source, IntSize(1, 1)) }
        }

    private fun image(value: Int): DrawImage = createDrawImage(IntSize(1, 1), intArrayOf(0xFF000000.toInt() or value))

    private fun images(frame: UiFrame): List<DrawImage> =
        frame.drawCommands
            .filterIsInstance<DrawCommand.BlitImage>()
            .map(DrawCommand.BlitImage::image)

    private class Frames(
        initial: DrawImage,
        private val closeFailure: Throwable? = null,
    ) : StateSource<DrawImage> {
        private var snapshot = StateSnapshot(StateRevision(0), initial)
        val observers: MutableList<(StateSnapshot<DrawImage>) -> Unit> = ArrayList()
        var subscriptions: Int = 0
        var closes: Int = 0
        var duringSubscribe: (() -> Unit)? = null

        override fun subscribe(observer: (StateSnapshot<DrawImage>) -> Unit): StateSubscription<DrawImage> {
            val initial = snapshot
            observers += observer
            subscriptions += 1
            duringSubscribe?.invoke()
            return StateSubscription(initial) {
                observers -= observer
                closes += 1
                closeFailure?.let { throw it }
            }
        }

        fun publish(image: DrawImage) {
            snapshot = StateSnapshot(StateRevision(Math.incrementExact(snapshot.revision.value)), image)
            observers.toList().forEach { observer -> observer(snapshot) }
        }
    }

    private class TrackingSource(
        private val source: CanvasSource,
    ) : CanvasSource {
        val identities: MutableList<CanvasId> = ArrayList()
        var closes: Int = 0

        override fun open(canvasId: CanvasId): CanvasBinding {
            identities += canvasId
            val binding = source.open(canvasId)
            return object : CanvasBinding by binding {
                override fun close() {
                    closes += 1
                    binding.close()
                }
            }
        }
    }

    private class ComparisonValue(
        private val value: Int,
        private val action: () -> Unit = {},
    ) {
        override fun equals(other: Any?): Boolean {
            action()
            return other is ComparisonValue && value == other.value
        }

        override fun hashCode(): Int = value
    }

    private class LocalHolder<T> {
        lateinit var delegate: ReadWriteProperty<Any?, T>

        var value: T
            get() = delegate.getValue(this, LocalHolder<T>::value)
            set(next) = delegate.setValue(this, LocalHolder<T>::value, next)
    }
}
