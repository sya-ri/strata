package dev.s7a.strata.integration.external

import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that an external modifier uses the public SPI without registration.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ExternalModifierIntegrationTest {
    @Test
    fun externalModifierUsesDefaultMeasureLayoutAndOwnsAllDeclaredPhases() {
        val probe = ExternalProbe()
        val modifier = ExternalModifierElement(probe)
        val root = ExternalElement(probe = probe, modifier = Modifier.Empty.then(modifier))
        val tree = UiTree()

        tree.update(root)
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Attach(ExternalNodeId.Root),
            ),
            probe.lifecycle,
        )
        assertEquals(IntSize(4, 4), tree.measure(Constraints(maxWidth = 10, maxHeight = 10)))
        tree.layout()
        val paint = tree.paint()
        assertEquals(2, paint.size)
        assertEquals(ArgbColor(0xFFFF00FF.toInt()), (paint[0] as DrawCommand.FillRectangle).color)
        assertEquals(ArgbColor(0xFF00FF00.toInt()), (paint[1] as DrawCommand.FillRectangle).color)
        assertEquals(
            listOf(UiText.Literal("modifier"), UiText.Literal("external")),
            tree.semantics().map(SemanticsEntry::semantics).map { value -> value.label },
        )
        assertEquals(InputResult.Consumed, tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))
        assertEquals(1, probe.modifierNode?.pointerCalls)
        val oldNode = probe.modifierNode
        oldNode?.invalidateForTest(DirtyPhase.Paint)
        tree.paint()
        assertEquals(2, oldNode?.paintCalls)
        updateAndRemoveModifier(tree, probe, modifier, oldNode)
    }

    private fun updateAndRemoveModifier(
        tree: UiTree,
        probe: ExternalProbe,
        modifier: ExternalModifierElement,
        oldNode: ExternalModifierNode?,
    ) {
        val updated =
            ExternalElement(
                probe = probe,
                color = ArgbColor(0xFF0000FF.toInt()),
                modifier = Modifier.Empty.then(modifier.copy(color = ArgbColor(0xFFFFFF00.toInt()))),
            )
        tree.update(updated)
        assertSame(oldNode, probe.modifierNode)
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        val updatedPaint = tree.paint()
        assertEquals(ArgbColor(0xFFFFFF00.toInt()), (updatedPaint[0] as DrawCommand.FillRectangle).color)
        assertEquals(ArgbColor(0xFF0000FF.toInt()), (updatedPaint[1] as DrawCommand.FillRectangle).color)
        assertTrue(probe.lifecycle.none { event -> event is ExternalLifecycleEvent.Detach })

        tree.update(ExternalElement(probe = probe, modifier = Modifier.Empty))
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Attach(ExternalNodeId.Root),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Modifier),
            ),
            probe.lifecycle,
        )
        tree.close()
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Attach(ExternalNodeId.Root),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Modifier),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Root),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Root),
            ),
            probe.lifecycle,
        )
    }

    @Test
    fun externalModifierTypeBridgeValidatesAndCreatesTypedNodes() {
        val probe = ExternalProbe()
        val description = ExternalModifierElement(probe)
        ExternalModifierElement.TYPE.validateErased(description)
        val node = ExternalModifierElement.TYPE.createErased(description)
        assertSame(ExternalModifierElement.TYPE, description.type)
        assertSame(probe.modifierNode, node)
    }

    @Test
    fun invalidNestedModifierRollsBackBeforeExternalUpdatesAndAllowsValidRetry() {
        val probe = ExternalProbe()
        val modifier = ExternalModifierElement(probe)
        val initialChild = childWithModifier(probe, modifier)
        val initialRoot = rootWithChild(probe, initialChild)
        val tree = UiTree()
        tree.update(initialRoot)
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val oldPaint = tree.paint()
        val oldNode = probe.modifierNode
        val oldLifecycle = probe.lifecycle.toList()
        val oldComponentUpdates = probe.componentUpdateCalls
        val oldModifierUpdates = probe.modifierUpdateCalls

        val invalidChild = childWithModifier(probe, modifier.copy(color = ArgbColor(0xFF0000FF.toInt()), valid = false))
        val invalidRoot = rootWithChild(probe, invalidChild, ArgbColor(0xFFFF0000.toInt()))
        assertThrows(IllegalArgumentException::class.java) { tree.update(invalidRoot) }
        assertEquals(oldPaint, tree.paint())
        assertSame(oldNode, probe.modifierNode)
        assertEquals(oldLifecycle, probe.lifecycle)
        assertEquals(oldComponentUpdates, probe.componentUpdateCalls)
        assertEquals(oldModifierUpdates, probe.modifierUpdateCalls)

        val validChild = childWithModifier(probe, modifier.copy(color = ArgbColor(0xFFFFFF00.toInt())))
        val validRoot = rootWithChild(probe, validChild, ArgbColor(0xFF0000FF.toInt()))
        tree.update(validRoot)
        assertEquals(oldComponentUpdates + 2, probe.componentUpdateCalls)
        assertEquals(oldModifierUpdates + 1, probe.modifierUpdateCalls)
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val colors = tree.paint().map { command -> (command as DrawCommand.FillRectangle).color }
        assertTrue(colors.contains(ArgbColor(0xFFFFFF00.toInt())))
        assertTrue(colors.contains(ArgbColor(0xFF0000FF.toInt())))
        tree.close()
    }

    private fun childWithModifier(
        probe: ExternalProbe,
        modifier: ExternalModifierElement,
    ): ExternalElement =
        ExternalElement(
            probe = probe,
            key = ElementKey("child"),
            nodeId = ExternalNodeId.Child,
            modifier = Modifier.Empty.then(modifier),
        )

    private fun rootWithChild(
        probe: ExternalProbe,
        child: ExternalElement,
        color: ArgbColor = ArgbColor(0xFF00FF00.toInt()),
    ): ExternalElement =
        ExternalElement(
            probe = probe,
            key = ElementKey("root"),
            color = color,
            children = listOf(child),
        )

    @Test
    fun externalModifierSeesOneVirtualChildAndComponentKeepsLogicalChildren() {
        val probe = ExternalProbe()
        val child =
            ExternalElement(
                probe = probe,
                key = ElementKey("child"),
                nodeId = ExternalNodeId.Child,
            )
        val root =
            ExternalElement(
                probe = probe,
                key = ElementKey("root"),
                children = listOf(child),
                modifier = Modifier.Empty.then(ExternalModifierElement(probe)),
            )
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        assertEquals(listOf(1), probe.modifierVirtualChildCounts)
        assertEquals(listOf(1, 0), probe.componentChildCounts)
        tree.close()
    }
}
