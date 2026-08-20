package dev.s7a.strata.integration.external

import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies that an external module can drive the retained session through only the public opt-in bridge.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ExternalRuntimeUiSessionIntegrationTest {
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
