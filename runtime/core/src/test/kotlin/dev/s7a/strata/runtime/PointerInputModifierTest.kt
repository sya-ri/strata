package dev.s7a.strata.runtime

import dev.s7a.strata.dsl.Spacer
import dev.s7a.strata.dsl.buildUi
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onMove
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.onRelease
import dev.s7a.strata.modifier.onScroll
import dev.s7a.strata.modifier.size
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
    fun simpleActionsUseTheirDocumentedConsumptionDefaults() {
        val calls = ArrayList<EventKind>()
        val modifier =
            Modifier.Empty
                .size(10, 10)
                .onPress { calls += EventKind.Press }
                .onRelease { calls += EventKind.Release }
                .onMove { calls += EventKind.Move }
                .onScroll { calls += EventKind.Scroll }
        val tree = tree(modifier)
        val position = IntOffset(1, 1)

        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Press(position, PointerButton.Primary)))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Release(position, PointerButton.Primary)))
        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(position)))
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Scroll(position, 0.0, 1.0)))
        assertEquals(listOf(EventKind.Press, EventKind.Release, EventKind.Move, EventKind.Scroll), calls)
        tree.close()
    }

    @Test
    fun clearingRetainedHoverEmitsOneExitAndLeavesTheTreeUsable() {
        val transitions = ArrayList<PointerHoverEvent>()
        val tree = tree(Modifier.Empty.size(10, 10).onHover(transitions::add))
        tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))

        tree.clearPointerHover()
        tree.clearPointerHover()
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

    private fun tree(modifier: Modifier): UiTree =
        UiTree().also { tree ->
            tree.update(buildUi { Spacer(modifier = modifier) })
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
        }

    private enum class EventKind {
        Press,
        Release,
        Move,
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

        data class Scroll(
            val event: PointerEvent.Scroll,
            val local: IntOffset,
        ) : Observation

        data class Hover(
            val event: PointerHoverEvent,
        ) : Observation
    }
}
