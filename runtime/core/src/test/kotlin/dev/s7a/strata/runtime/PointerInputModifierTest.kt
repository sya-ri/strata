@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onCapturedPointerEvent
import dev.s7a.strata.modifier.onDrag
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onScroll
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies typed pointer action modifiers and hover transitions through the retained input pipeline.
 */
internal class PointerInputModifierTest {
    @Test
    fun everyTypedEventAndRawEventReceiveExactLocalInput() {
        val observations = ArrayList<Observation>()
        val modifier =
            Modifier.Empty
                .size(10, 10)
                .onPointerEvent { event, local ->
                    observations += Observation.Raw(event, local)
                    InputResult.Ignored
                }.onPress { event, local ->
                    observations += Observation.Press(event, local)
                    InputResult.Ignored
                }.onRelease { event, local ->
                    observations += Observation.Release(event, local)
                    InputResult.Ignored
                }.onMove { event, local ->
                    observations += Observation.Move(event, local)
                    InputResult.Ignored
                }.onScroll { event, local ->
                    observations += Observation.Scroll(event, local)
                    InputResult.Ignored
                }.onHover { event -> observations += Observation.Hover(event) }
        val tree = tree(modifier)

        val inside = IntOffset(3, 4)
        val move = PointerEvent.Move(inside)
        assertEquals(InputResult.Ignored, tree.dispatchPointer(move))
        assertEquals(
            listOf(
                Observation.Hover(PointerHoverEvent.Enter),
                Observation.Move(move, inside),
                Observation.Raw(move, inside),
            ),
            observations,
        )

        observations.clear()
        val press = PointerEvent.Press(inside, PointerButton.Secondary)
        val release = PointerEvent.Release(inside, PointerButton.Middle)
        val scroll = PointerEvent.Scroll(inside, 1.25, -2.5)
        assertEquals(InputResult.Ignored, tree.dispatchPointer(press))
        assertEquals(InputResult.Ignored, tree.dispatchPointer(release))
        assertEquals(InputResult.Ignored, tree.dispatchPointer(scroll))
        assertEquals(
            listOf(
                Observation.Press(press, inside),
                Observation.Raw(press, inside),
                Observation.Release(release, inside),
                Observation.Raw(release, inside),
                Observation.Scroll(scroll, inside),
                Observation.Raw(scroll, inside),
            ),
            observations,
        )

        observations.clear()
        tree.dispatchPointer(PointerEvent.Move(inside))
        assertEquals(listOf(Observation.Move(move, inside), Observation.Raw(move, inside)), observations)
        observations.clear()
        tree.dispatchPointer(PointerEvent.Move(IntOffset(10, 10)))
        assertEquals(listOf(Observation.Hover(PointerHoverEvent.Exit)), observations)
        tree.close()
    }

    @Test
    fun dragHandlersReceiveTypedLocalInputAndUpdateHover() {
        val observations = ArrayList<Observation>()
        val modifier =
            Modifier.Empty
                .size(10, 10)
                .onPointerEvent { event, local ->
                    observations += Observation.Raw(event, local)
                    InputResult.Ignored
                }.onDrag { event, local ->
                    observations += Observation.Drag(event, local)
                    InputResult.Ignored
                }.onHover { event -> observations += Observation.Hover(event) }
        val tree = tree(modifier)
        val inside = IntOffset(3, 4)
        val drag = PointerEvent.Drag(inside, PointerButton.Primary, 1.25, -0.5)

        assertEquals(InputResult.Ignored, tree.dispatchPointer(drag))
        assertEquals(
            listOf(
                Observation.Hover(PointerHoverEvent.Enter),
                Observation.Drag(drag, inside),
                Observation.Raw(drag, inside),
            ),
            observations,
        )
        observations.clear()
        tree.dispatchPointer(PointerEvent.Drag(IntOffset(10, 10), PointerButton.Primary, 7.0, 6.0))
        assertEquals(listOf(Observation.Hover(PointerHoverEvent.Exit)), observations)
        tree.close()
    }

    @Test
    fun simpleActionsUseTheirDocumentedConsumptionDefaults() {
        val calls = ArrayList<EventKind>()
        val modifier =
            Modifier.Empty
                .size(10, 10)
                .onPress { calls += EventKind.Press }
                .onRelease { calls += EventKind.Release }
                .onMove { calls += EventKind.Move }
                .onDrag { calls += EventKind.Drag }
                .onScroll { calls += EventKind.Scroll }
        val tree = tree(modifier)
        val position = IntOffset(1, 1)

        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(position, PointerButton.Primary)))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Release(position, PointerButton.Primary)))
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(position)))
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Drag(position, PointerButton.Primary, 1.0, -1.0)))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Scroll(position, 0.0, 1.0)))
        assertEquals(listOf(EventKind.Press, EventKind.Release, EventKind.Move, EventKind.Drag, EventKind.Scroll), calls)
        tree.close()
    }

    @Test
    fun clearingRetainedHoverEmitsOneExitAndLeavesTheTreeUsable() {
        val transitions = ArrayList<PointerHoverEvent>()
        val tree = tree(Modifier.Empty.size(10, 10).onHover(transitions::add))
        tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))

        tree.clearInputState()
        tree.clearInputState()
        assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit), transitions)

        tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit, PointerHoverEvent.Enter), transitions)
        tree.close()
    }

    @Test
    fun callbackFailurePoisonsTheTreeAndPreservesIdentity() {
        val primary = IllegalArgumentException("pointer callback")
        val tree = tree(Modifier.Empty.size(10, 10).onPress { throw primary })

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
            }
        assertSame(primary, failure)
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    @Test
    fun capturedEventsRemainExclusiveOutsideBoundsAndIgnoredResultsDoNotBubble() {
        val observed = ArrayList<Observation.Raw>()
        val cancellations = ArrayList<PointerButton>()
        val hover = ArrayList<PointerHoverEvent>()
        val fallback = ArrayList<PointerEvent>()
        val tree =
            tree(
                Modifier.Empty
                    .size(10, 10)
                    .onPointerEvent { event, _ ->
                        fallback += event
                        InputResult.Consumed
                    }.onCapturedPointerEvent(cancellations::add) { event, local ->
                        observed += Observation.Raw(event, local)
                        if (event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
                    }.onHover(hover::add),
            )
        tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        observed.clear()
        fallback.clear()
        val press = PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)
        val inside = PointerEvent.Move(IntOffset(2, 3))
        val outside = PointerEvent.Move(IntOffset(-3, 15))
        val drag = PointerEvent.Drag(IntOffset(20, -4), PointerButton.Primary, 23.0, -19.0)
        val release = PointerEvent.Release(IntOffset(4, 5), PointerButton.Primary)
        val events = listOf(press, inside, outside, drag, release)

        events.forEach { event -> assertEquals(InputResult.Consumed, tree.dispatchPointer(event)) }

        assertEquals(events.map { event -> Observation.Raw(event, event.position) }, observed)
        assertEquals(emptyList<PointerEvent>(), fallback)
        assertEquals(listOf(PointerHoverEvent.Enter, PointerHoverEvent.Exit), hover)
        tree.clearInputState()
        assertEquals(emptyList<PointerButton>(), cancellations)
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(20, 20))))
        tree.close()
    }

    @Test
    fun onlyConsumedPressesAcquireCapture() {
        val cancellations = ArrayList<PointerButton>()
        val events = ArrayList<PointerEvent>()
        val tree =
            tree(
                Modifier.Empty.size(10, 10).onCapturedPointerEvent(cancellations::add) { event, _ ->
                    events += event
                    if (event is PointerEvent.Press) InputResult.Ignored else InputResult.Consumed
                },
            )
        val press = PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)
        val drag = PointerEvent.Drag(IntOffset(1, 1), PointerButton.Primary, 0.0, 0.0)
        assertEquals(InputResult.Ignored, tree.dispatchPointer(press))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(drag))
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(20, 20))))
        tree.close()
        assertEquals(listOf(press, drag), events)
        assertEquals(emptyList<PointerButton>(), cancellations)
    }

    @Test
    fun otherButtonsAndScrollingKeepHitTestingWithoutStealingCapture() {
        val first = ArrayList<PointerEvent>()
        val second = ArrayList<PointerEvent>()
        val cancellations = ArrayList<PointerButton>()
        val probe = TestProbe(inputResult = InputResult.Ignored)
        val tree = UiTree()
        tree.update(
            probe.root(
                listOf(
                    probe.element(
                        TestProbe.ProbeId("first"),
                        modifier =
                            Modifier.Empty.onCapturedPointerEvent(cancellations::add) { event, _ ->
                                first += event
                                InputResult.Consumed
                            },
                    ),
                    probe.element(
                        TestProbe.ProbeId("second"),
                        modifier =
                            Modifier.Empty.onCapturedPointerEvent(cancellations::add) { event, _ ->
                                second += event
                                InputResult.Consumed
                            },
                    ),
                ),
            ),
        )
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        val press = PointerEvent.Press(IntOffset(1, 0), PointerButton.Primary)
        val other = IntOffset(3, 0)
        val ordinary =
            listOf(
                PointerEvent.Press(other, PointerButton.Secondary),
                PointerEvent.Drag(other, PointerButton.Secondary, 1.0, 0.0),
                PointerEvent.Release(other, PointerButton.Secondary),
                PointerEvent.Scroll(other, 0.0, 1.0),
            )
        val captured =
            listOf(
                PointerEvent.Move(other),
                PointerEvent.Drag(other, PointerButton.Primary, 2.0, 0.0),
                PointerEvent.Release(other, PointerButton.Primary),
            )

        tree.dispatchPointer(press)
        ordinary.forEach(tree::dispatchPointer)
        captured.forEach(tree::dispatchPointer)
        tree.close()

        assertEquals(listOf(press) + captured, first)
        assertEquals(ordinary, second)
        assertEquals(emptyList<PointerButton>(), cancellations)
    }

    @Test
    fun callbackUpdatesKeepCaptureAndReadOnlyLatestCallbacks() {
        val oldEvents = ArrayList<PointerEvent>()
        val newEvents = ArrayList<PointerEvent>()
        val oldCancellations = ArrayList<PointerButton>()
        val newCancellations = ArrayList<PointerButton>()
        val tree =
            tree(
                Modifier.Empty.size(10, 10).onCapturedPointerEvent(oldCancellations::add) { event, _ ->
                    oldEvents += event
                    InputResult.Consumed
                },
            )
        val press = PointerEvent.Press(IntOffset(1, 1), PointerButton.Auxiliary(4))
        tree.dispatchPointer(press)
        val revision = tree.currentRevision()
        tree.update(
            evaluateComponentTree {
                Spacer(
                    modifier =
                        Modifier.Empty.size(10, 10).onCapturedPointerEvent(newCancellations::add) { event, _ ->
                            newEvents += event
                            InputResult.Ignored
                        },
                )
            },
        )
        assertEquals(revision, tree.currentRevision())
        val outside = PointerEvent.Drag(IntOffset(-1, -2), PointerButton.Auxiliary(4), -2.0, -3.0)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(outside))
        tree.clearInputState()
        tree.close()

        assertEquals(listOf(press), oldEvents)
        assertEquals(listOf(outside), newEvents)
        assertEquals(emptyList<PointerButton>(), oldCancellations)
        assertEquals(listOf(PointerButton.Auxiliary(4)), newCancellations)
    }

    @Test
    fun removingOrReplacingCapturedModifierCancelsBeforeItsCallbackIsDisposed() {
        for (replacement in listOf(Modifier.Empty, Modifier.Empty.onPointerEvent { _, _ -> InputResult.Ignored })) {
            val cancellations = ArrayList<PointerButton>()
            val tree =
                tree(
                    Modifier.Empty.size(10, 10).onCapturedPointerEvent(cancellations::add) { _, _ -> InputResult.Consumed },
                )
            tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
            tree.update(evaluateComponentTree { Spacer(modifier = Modifier.Empty.size(10, 10).then(replacement)) })
            assertEquals(listOf(PointerButton.Primary), cancellations)
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
            tree.close()
            assertEquals(listOf(PointerButton.Primary), cancellations)
        }
    }

    @Test
    fun releaseFailureDoesNotCancelAnAlreadyCompletedGesture() {
        val primary = IllegalArgumentException("release")
        val cancellations = ArrayList<PointerButton>()
        val tree =
            tree(
                Modifier.Empty.size(10, 10).onCapturedPointerEvent(cancellations::add) { event, _ ->
                    if (event is PointerEvent.Release) throw primary
                    InputResult.Consumed
                },
            )
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary))
        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                tree.dispatchPointer(PointerEvent.Release(IntOffset(-1, -1), PointerButton.Primary))
            }
        assertSame(primary, failure)
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
        assertEquals(emptyList<PointerButton>(), cancellations)
    }

    @Test
    fun terminalCancellationRunsAfterEveryNodeRejectsInvalidation() {
        val probe = TestProbe(inputResult = InputResult.Ignored)
        val child = TestProbe.ProbeId("child")
        val tree = UiTree()
        var cancellations = 0
        tree.update(
            probe.root(
                listOf(probe.element(child)),
                modifier =
                    Modifier.Empty.onCapturedPointerEvent(
                        onCancel = { _ ->
                            cancellations += 1
                            assertEquals(TreeState.Closed, tree.state)
                            assertThrows(IllegalStateException::class.java) {
                                probe.nodeForTag(child).invalidateForTest(DirtyMask.of(DirtyPhase.Paint))
                            }
                        },
                    ) { _, _ -> InputResult.Consumed },
            ),
        )
        tree.measure(Constraints.fixed(10, 10))
        tree.layout()
        tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 0), PointerButton.Primary))
        tree.close()
        assertEquals(1, cancellations)
    }

    private fun tree(modifier: Modifier): UiTree =
        UiTree().also { tree ->
            tree.update(evaluateComponentTree { Spacer(modifier = modifier) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
        }

    private enum class EventKind {
        Press,
        Release,
        Move,
        Drag,
        Scroll,
    }

    private sealed interface Observation {
        data class Raw(
            val event: PointerEvent,
            val local: IntOffset,
        ) : Observation

        data class Press(
            val event: PointerEvent.Press,
            val local: IntOffset,
        ) : Observation

        data class Release(
            val event: PointerEvent.Release,
            val local: IntOffset,
        ) : Observation

        data class Move(
            val event: PointerEvent.Move,
            val local: IntOffset,
        ) : Observation

        data class Drag(
            val event: PointerEvent.Drag,
            val local: IntOffset,
        ) : Observation

        data class Scroll(
            val event: PointerEvent.Scroll,
            val local: IntOffset,
        ) : Observation

        data class Hover(
            val event: PointerHoverEvent,
        ) : Observation
    }
}
