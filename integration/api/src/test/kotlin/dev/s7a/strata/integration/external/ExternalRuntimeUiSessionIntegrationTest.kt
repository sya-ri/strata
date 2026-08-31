package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.KeyboardModifiers
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that an external module can drive the retained session through only the public opt-in bridge.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ExternalRuntimeUiSessionIntegrationTest {
    @Test
    fun publicSessionTraversesOnlyVisibleVirtualRowsAcrossSynchronousRebuilds() {
        val state = VirtualListState<Int>()
        val materialized = ArrayList<Int>()
        val activations = ArrayList<Int>()
        var contentCalls = 0
        val session =
            createRuntimeUiSession {
                contentCalls += 1
                evaluateComponentTree {
                    VirtualList(
                        itemCount = 10_000,
                        itemAt = { index -> index },
                        keyAt = { index -> index },
                        state = state,
                        viewportSize = IntSize(80, 30),
                        rowHeight = 10,
                    ) { item ->
                        materialized += item
                        Spacer(
                            modifier =
                                Modifier.Empty
                                    .size(80, 10)
                                    .onActivate { activations += item },
                        )
                    }
                }
            }
        try {
            session.attach()
            session.frame(Constraints.fixed(80, 30))
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
            assertEquals(listOf(0), activations)

            assertTrue(state.jumpToIndex(1))
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
            assertEquals(listOf(0, 0), activations)
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))
            assertEquals(listOf(0, 0, 1), activations)

            materialized.clear()
            assertTrue(state.jumpToIndex(5_000))
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)))
            assertEquals(listOf(4_999, 5_000, 5_001, 5_002, 5_003), materialized)
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Enter, 0)))

            materialized.clear()
            assertTrue(state.jumpToIndex(9_000))
            assertEquals(
                InputResult.Consumed,
                session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0, KeyboardModifiers(shift = true))),
            )
            assertEquals(listOf(8_999, 9_000, 9_001, 9_002, 9_003), materialized)
            assertEquals(InputResult.Consumed, session.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Space, 0)))

            assertEquals(listOf(0, 0, 1, 5_000, 9_002), activations)
            assertEquals(1, contentCalls)
        } finally {
            session.close()
        }
    }

    @Test
    fun externalElementRendersReceivesInputAndReattachesWithoutRegistration() {
        val probe = ExternalProbe()
        val root = ExternalElement(probe = probe, width = 4, height = 4)
        val session = createRuntimeUiSession { root }

        session.attach()
        assertEquals(
            InputResult.Ignored,
            session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))),
        )
        val firstFrame = session.frame(Constraints.fixed(4, 4))
        assertEquals(IntSize(4, 4), firstFrame.size)
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)))
        assertEquals(1, probe.componentNodes.size)
        val retainedNode = probe.componentNodes.getValue(ExternalNodeId.Root)
        assertEquals(1, retainedNode.paints)

        retainedNode.invalidateForTest(DirtyPhase.Paint)
        session.frame(Constraints.fixed(4, 4))
        assertSame(retainedNode, probe.componentNodes.getValue(ExternalNodeId.Root))
        assertEquals(2, retainedNode.paints)

        session.detach()
        session.attach()
        assertEquals(
            InputResult.Ignored,
            session.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))),
        )
        val secondFrame = session.frame(Constraints.fixed(4, 4))
        assertEquals(firstFrame.size, secondFrame.size)
        assertEquals(InputResult.Consumed, session.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)))

        session.close()
        session.close()
        assertEquals(1, probe.lifecycle.count { event -> event is ExternalLifecycleEvent.Detach })
        assertEquals(1, probe.lifecycle.count { event -> event is ExternalLifecycleEvent.Dispose })
    }
}
