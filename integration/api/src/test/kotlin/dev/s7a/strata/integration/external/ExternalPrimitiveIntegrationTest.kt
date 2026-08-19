package dev.s7a.strata.integration.external

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies that an external primitive uses the public API without registration.
 */
internal class ExternalPrimitiveIntegrationTest {
    @Test
    fun usesAnExternalPrimitiveWithoutRegistration() {
        val tree = UiTree()
        val probe = ExternalProbe()
        val child =
            ExternalElement(
                probe = probe,
                key = ElementKey("child"),
                width = 2,
                label = UiText.Literal("child"),
                nodeId = ExternalNodeId.Child,
            )
        val root = ExternalElement(probe = probe, key = ElementKey("root"), children = listOf(child))
        tree.update(root)
        assertEquals(IntSize(4, 4), tree.measure(Constraints(maxWidth = 20, maxHeight = 20)))
        tree.layout()
        val firstPaint = tree.paint()
        assertEquals(2, firstPaint.size)
        assertEquals(IntRect(0, 0, 4, 4), (firstPaint[0] as DrawCommand.FillRectangle).bounds)
        assertEquals(IntRect(0, 0, 2, 4), (firstPaint[1] as DrawCommand.FillRectangle).bounds)
        assertEquals(
            InputResult.Consumed,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
        )
        val entries = tree.semantics()
        assertEquals(2, entries.size)
        assertEquals(UiText.Literal("external"), entries[0].semantics.label)
        assertEquals(UiText.Literal("child"), entries[1].semantics.label)
        assertEquals(IntRect(0, 0, 4, 4), entries[0].bounds)
        assertEquals(IntRect(0, 0, 2, 4), entries[1].bounds)
        tree.update(
            ExternalElement(
                probe = probe,
                key = ElementKey("root"),
                color = ArgbColor(0xFFFF0000.toInt()),
                children = listOf(child),
            ),
        )
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val secondPaint = tree.paint()[0] as DrawCommand.FillRectangle
        assertEquals(ArgbColor(0xFFFF0000.toInt()), secondPaint.color)
        tree.close()
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Root),
                ExternalLifecycleEvent.Attach(ExternalNodeId.Child),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Child),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Child),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Root),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Root),
            ),
            probe.lifecycle,
        )
    }
}
