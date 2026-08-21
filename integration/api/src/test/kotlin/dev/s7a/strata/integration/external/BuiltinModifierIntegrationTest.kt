package dev.s7a.strata.integration.external

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxHeight
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.fillMaxWidth
import dev.s7a.strata.modifier.height
import dev.s7a.strata.modifier.heightIn
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.semantics
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.sizeIn
import dev.s7a.strata.modifier.width
import dev.s7a.strata.modifier.widthIn
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.TreeState
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.text.TranslationFallback
import dev.s7a.strata.text.UiText
import dev.s7a.strata.text.UiTextArgument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies built-in modifiers through an external element implemented against the public API.
 */
internal class BuiltinModifierIntegrationTest {
    @Test
    fun sizePoliciesClampRangesSupportUnboundedAxesAndPreserveSingleAxisBehavior() {
        assertEquals(
            IntSize(10, 12),
            measure(Modifier.Empty.size(20, 30), Constraints(maxWidth = 10, maxHeight = 12)),
        )
        assertEquals(
            IntSize(8, 4),
            measure(
                Modifier.Empty.sizeIn(minWidth = 8, minHeight = 0, maxWidth = Int.MAX_VALUE, maxHeight = 7),
                Constraints(minWidth = 5, maxWidth = 20, minHeight = 3, maxHeight = 10),
            ),
        )
        assertEquals(
            IntSize(10, 40),
            measure(
                Modifier.Empty.sizeIn(minWidth = 0, minHeight = 50, maxWidth = 5, maxHeight = 60),
                Constraints(minWidth = 10, maxWidth = 20, minHeight = 30, maxHeight = 40),
            ),
        )
        assertEquals(IntSize(7, 4), measure(Modifier.Empty.width(7), Constraints(maxWidth = 10, maxHeight = 10)))
        assertEquals(IntSize(4, 9), measure(Modifier.Empty.height(9), Constraints(maxWidth = 10, maxHeight = 10)))
        assertEquals(
            IntSize(8, 7),
            measure(
                Modifier.Empty.widthIn(min = 6, max = 8),
                Constraints(maxWidth = 10),
                width = 9,
                height = 7,
            ),
        )
        assertEquals(
            IntSize(6, 8),
            measure(
                Modifier.Empty.heightIn(min = 6, max = 8),
                Constraints(maxHeight = 10),
                width = 6,
                height = 9,
            ),
        )
    }

    @Test
    fun fillPoliciesPreserveUnboundedAxesAndResolveDisjointSingleAxisRanges() {
        assertEquals(
            IntSize(10, 12),
            measure(Modifier.Empty.fillMaxSize(), Constraints(maxWidth = 10, maxHeight = 12)),
        )
        assertEquals(
            IntSize(4, 4),
            measure(Modifier.Empty.fillMaxSize(), Constraints()),
        )
        assertEquals(
            IntSize(10, 4),
            measure(Modifier.Empty.fillMaxWidth(), Constraints(maxWidth = 10)),
        )
        assertEquals(
            IntSize(4, 12),
            measure(Modifier.Empty.fillMaxHeight(), Constraints(maxHeight = 12)),
        )
        assertEquals(
            IntSize(10, 7),
            measure(
                Modifier.Empty.widthIn(min = 0, max = 5),
                Constraints(minWidth = 10, maxWidth = 20, maxHeight = 10),
                width = 13,
                height = 7,
            ),
        )
        assertEquals(
            IntSize(6, 40),
            measure(
                Modifier.Empty.heightIn(min = 50, max = 60),
                Constraints(minHeight = 30, maxHeight = 40, maxWidth = 10),
                width = 6,
                height = 13,
            ),
        )
        assertEquals(
            IntSize(5, 6),
            measure(
                Modifier.Empty.fillMaxSize(),
                Constraints(minWidth = 5, minHeight = 6),
            ),
        )
    }

    @Test
    fun paddingReducesConstraintsRestoresInsetsAndRespectsSourceOrder() {
        assertEquals(
            IntSize(2, 2),
            measure(Modifier.Empty.size(2, 2).padding(1), Constraints(maxWidth = 10, maxHeight = 10)),
        )
        assertEquals(
            IntSize(4, 4),
            measure(Modifier.Empty.padding(1).size(2, 2), Constraints(maxWidth = 10, maxHeight = 10)),
        )
        assertEquals(
            IntSize(2, 2),
            measure(Modifier.Empty.padding(3), Constraints.fixed(2, 2)),
        )
        assertEquals(
            IntSize(7, 11),
            measure(
                Modifier.Empty.padding(left = 1, top = 3, right = 2, bottom = 4),
                Constraints(maxWidth = 10),
            ),
        )
        assertEquals(
            IntSize(8, 10),
            measure(Modifier.Empty.padding(horizontal = 2, vertical = 3), Constraints(maxWidth = 20, maxHeight = 20)),
        )
    }

    @Test
    fun asymmetricPaddingPassesZeroConstraintsPlacesChildAndTranslatesSemantics() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val insets = Insets(left = 3, top = 4, right = 5, bottom = 6)
        tree.update(element(probe = probe, modifier = Modifier.Empty.padding(insets)))

        tree.measure(Constraints.fixed(2, 2))
        tree.layout()

        assertEquals(Constraints.fixed(0, 0), probe.componentMeasureConstraints.single())
        assertEquals(1, probe.componentNodes.getValue(ExternalNodeId.Root).layouts)
        assertEquals(IntRect(3, 4, 3, 4), tree.semantics().single().bounds)
        tree.close()
    }

    @Test
    fun horizontalAndVerticalPaddingOverflowRetiresNodesExactlyOnce() {
        assertPaddingOverflow(width = Int.MAX_VALUE, height = 4, insets = Insets(left = 1))
        assertPaddingOverflow(width = 4, height = Int.MAX_VALUE, insets = Insets(top = 1))
    }

    @Test
    fun backgroundAndPaddingSourceOrderChangesLocalPaintBounds() {
        val insets = Insets(left = 1, top = 2, right = 3, bottom = 4)
        val outerBackgroundTree = UiTree()
        val outerBackgroundProbe = ExternalProbe()
        outerBackgroundTree.update(
            element(
                probe = outerBackgroundProbe,
                modifier =
                    Modifier.Empty
                        .background(ArgbColor(0xFFFF0000.toInt()))
                        .padding(insets),
            ),
        )
        outerBackgroundTree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        outerBackgroundTree.layout()
        assertEquals(
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 8, 10), ArgbColor(0xFFFF0000.toInt())),
                DrawCommand.FillRectangle(IntRect(1, 2, 5, 6), ArgbColor(0xFF00FF00.toInt())),
            ),
            outerBackgroundTree.paint(),
        )
        outerBackgroundTree.close()

        val innerBackgroundTree = UiTree()
        val innerBackgroundProbe = ExternalProbe()
        innerBackgroundTree.update(
            element(
                probe = innerBackgroundProbe,
                modifier =
                    Modifier.Empty
                        .padding(insets)
                        .background(ArgbColor(0xFFFF0000.toInt())),
            ),
        )
        innerBackgroundTree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        innerBackgroundTree.layout()
        assertEquals(
            listOf(
                DrawCommand.FillRectangle(IntRect(1, 2, 5, 6), ArgbColor(0xFFFF0000.toInt())),
                DrawCommand.FillRectangle(IntRect(1, 2, 5, 6), ArgbColor(0xFF00FF00.toInt())),
            ),
            innerBackgroundTree.paint(),
        )
        innerBackgroundTree.close()
    }

    @Test
    fun translatedSemanticsRetainsTypedArgumentsAndFallbackBeforeDescendants() {
        val translated =
            UiText.Translated(
                key = "chat.message.count",
                arguments = listOf(UiTextArgument.IntValue(7)),
                fallback = "Seven messages",
            )
        val modifierSemantics = Semantics(label = translated)
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            element(
                probe = probe,
                label = UiText.Literal("root"),
                modifier = Modifier.Empty.semantics(modifierSemantics),
                children =
                    listOf(
                        element(
                            probe = probe,
                            key = ElementKey("child"),
                            nodeId = ExternalNodeId.Child,
                            label = UiText.Literal("child"),
                        ),
                    ),
            ),
        )
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        val entries = tree.semantics()
        assertSame(translated, entries[0].semantics.label)
        assertSame(modifierSemantics, entries[0].semantics)
        assertEquals(UiTextArgument.IntValue(7), translated.arguments.single())
        assertEquals(TranslationFallback.Literal("Seven messages"), translated.fallback)
        assertEquals(UiText.Literal("root"), entries[1].semantics.label)
        assertEquals(UiText.Literal("child"), entries[2].semantics.label)
        tree.close()
    }

    @Test
    fun backgroundCommandsUseLocalBoundsAndParentBeforeChildOrder() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            element(
                probe = probe,
                modifier = Modifier.Empty.background(ArgbColor(0xFFFF0000.toInt())),
                children =
                    listOf(
                        element(
                            probe = probe,
                            key = ElementKey("child"),
                            width = 2,
                            height = 2,
                            nodeId = ExternalNodeId.Child,
                            modifier = Modifier.Empty.background(ArgbColor(0xFF0000FF.toInt())),
                        ),
                    ),
            ),
        )
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        assertEquals(
            listOf(
                DrawCommand.FillRectangle(IntRect(0, 0, 4, 4), ArgbColor(0xFFFF0000.toInt())),
                DrawCommand.FillRectangle(IntRect(0, 0, 4, 4), ArgbColor(0xFF00FF00.toInt())),
                DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(0xFF0000FF.toInt())),
                DrawCommand.FillRectangle(IntRect(0, 0, 2, 2), ArgbColor(0xFF00FF00.toInt())),
            ),
            tree.paint(),
        )
        tree.close()
    }

    @Test
    fun semanticsModifiersRemainUnresolvedAndPrecedeComponentDescendants() {
        val rootModifier = Semantics(label = UiText.Literal("root modifier"))
        val childModifier = Semantics(label = UiText.Literal("child modifier"))
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            element(
                probe = probe,
                label = UiText.Literal("root"),
                modifier = Modifier.Empty.semantics(rootModifier),
                children =
                    listOf(
                        element(
                            probe = probe,
                            key = ElementKey("child"),
                            width = 2,
                            nodeId = ExternalNodeId.Child,
                            label = UiText.Literal("child"),
                            modifier = Modifier.Empty.semantics(childModifier),
                        ),
                    ),
            ),
        )
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()

        val entries = tree.semantics()
        assertEquals(
            listOf(
                UiText.Literal("root modifier"),
                UiText.Literal("root"),
                UiText.Literal("child modifier"),
                UiText.Literal("child"),
            ),
            entries.map { entry -> entry.semantics.label },
        )
        assertEquals(IntRect(0, 0, 4, 4), entries[0].bounds)
        assertEquals(IntRect(0, 0, 2, 4), entries[2].bounds)
        assertSame(rootModifier, entries[0].semantics)
        tree.close()
    }

    @Test
    fun backgroundUpdateInvalidatesOnlyPaintAndEqualValueDoesNoWork() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val key = ElementKey("root")
        tree.update(
            element(
                probe = probe,
                key = key,
                modifier = Modifier.Empty.background(ArgbColor(0xFFFF0000.toInt())),
            ),
        )
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        val component = probe.componentNodes.getValue(ExternalNodeId.Root)
        val measures = component.measures
        val paints = component.paints
        val semantics = component.semanticsCalls

        tree.update(
            element(
                probe = probe,
                key = key,
                modifier = Modifier.Empty.background(ArgbColor(0xFF0000FF.toInt())),
            ),
        )
        val updatedPaint = tree.paint().first() as DrawCommand.FillRectangle
        tree.semantics()
        assertEquals(ArgbColor(0xFF0000FF.toInt()), updatedPaint.color)
        assertEquals(measures, component.measures)
        assertEquals(paints, component.paints)
        assertEquals(semantics, component.semanticsCalls)

        tree.update(
            element(
                probe = probe,
                key = key,
                modifier = Modifier.Empty.background(ArgbColor(0xFF0000FF.toInt())),
            ),
        )
        tree.paint()
        tree.semantics()
        assertEquals(measures, component.measures)
        assertEquals(paints, component.paints)
        assertEquals(semantics, component.semanticsCalls)
        tree.close()
    }

    @Test
    fun semanticsUpdateInvalidatesOnlyModifierSemantics() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val key = ElementKey("root")
        tree.update(
            element(
                probe = probe,
                key = key,
                modifier = Modifier.Empty.semantics(Semantics(label = UiText.Literal("first"))),
            ),
        )
        tree.measure(Constraints(maxWidth = 10, maxHeight = 10))
        tree.layout()
        tree.paint()
        tree.semantics()
        val component = probe.componentNodes.getValue(ExternalNodeId.Root)
        val measures = component.measures
        val paints = component.paints
        val componentSemantics = component.semanticsCalls

        tree.update(
            element(
                probe = probe,
                key = key,
                modifier = Modifier.Empty.semantics(Semantics(label = UiText.Literal("second"))),
            ),
        )
        val entries = tree.semantics()
        assertEquals(UiText.Literal("second"), entries.first().semantics.label)
        assertEquals(measures, component.measures)
        assertEquals(paints, component.paints)
        assertEquals(componentSemantics, component.semanticsCalls)
        tree.close()
    }

    @Test
    fun sizeUpdateRemeasuresTheAffectedExternalComponentOnce() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val key = ElementKey("root")
        tree.update(element(probe = probe, key = key, modifier = Modifier.Empty.size(4, 4)))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val firstMeasures = probe.componentNodes.getValue(ExternalNodeId.Root).measures

        tree.update(element(probe = probe, key = key, modifier = Modifier.Empty.size(8, 8)))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        val secondMeasures = probe.componentNodes.getValue(ExternalNodeId.Root).measures
        assertEquals(firstMeasures + 1, secondMeasures)
        tree.close()
    }

    @Test
    fun paddingUpdateRemeasuresChangedInsetsOnceAndEqualInsetsNotAtAll() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val key = ElementKey("root")
        val initialInsets = Insets(left = 1, top = 2, right = 1, bottom = 2)
        val changedInsets = Insets(left = 3, top = 1, right = 2, bottom = 4)
        tree.update(element(probe = probe, key = key, modifier = Modifier.Empty.padding(initialInsets)))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val initialNode = probe.componentNodes.getValue(ExternalNodeId.Root)
        val initialMeasures = initialNode.measures

        tree.update(element(probe = probe, key = key, modifier = Modifier.Empty.padding(changedInsets)))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        val changedMeasures = initialNode.measures
        assertSame(initialNode, probe.componentNodes.getValue(ExternalNodeId.Root))
        assertEquals(initialMeasures + 1, changedMeasures)

        tree.layout()
        tree.update(element(probe = probe, key = key, modifier = Modifier.Empty.padding(changedInsets)))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        assertSame(initialNode, probe.componentNodes.getValue(ExternalNodeId.Root))
        assertEquals(changedMeasures, initialNode.measures)
        tree.close()
    }

    @Test
    fun exactSizeBelowParentMinimumsIsRaisedToThoseMinimums() {
        assertEquals(
            IntSize(5, 6),
            measure(
                Modifier.Empty.size(2, 3),
                Constraints(minWidth = 5, maxWidth = 10, minHeight = 6, maxHeight = 10),
            ),
        )
    }

    private fun measure(
        modifier: Modifier,
        constraints: Constraints,
        width: Int = 4,
        height: Int = 4,
    ): IntSize {
        val tree = UiTree()
        val probe = ExternalProbe()
        tree.update(element(probe = probe, width = width, height = height, modifier = modifier))
        val size = tree.measure(constraints)
        tree.close()
        return size
    }

    private fun assertPaddingOverflow(
        width: Int,
        height: Int,
        insets: Insets,
    ) {
        val tree = UiTree()
        val probe = ExternalProbe()
        tree.update(element(probe = probe, width = width, height = height, modifier = Modifier.Empty.padding(insets)))
        val node = probe.componentNodes.getValue(ExternalNodeId.Root)

        assertThrows(ArithmeticException::class.java) { tree.measure(Constraints()) }
        assertEquals(TreeState.Poisoned, tree.state)
        assertEquals(
            listOf(
                ExternalLifecycleEvent.Attach(ExternalNodeId.Root),
                ExternalLifecycleEvent.Detach(ExternalNodeId.Root),
                ExternalLifecycleEvent.Dispose(ExternalNodeId.Root),
            ),
            probe.lifecycle,
        )
        assertThrows(IllegalStateException::class.java) { node.invalidateForTest(DirtyPhase.Paint) }
        val lifecycleAfterFailure = probe.lifecycle.toList()
        tree.close()
        assertEquals(lifecycleAfterFailure, probe.lifecycle)
        assertThrows(IllegalStateException::class.java) { node.invalidateForTest(DirtyPhase.Paint) }
    }

    private fun element(
        probe: ExternalProbe,
        key: ElementKey<*> = ElementKey("root"),
        width: Int = 4,
        height: Int = 4,
        color: ArgbColor = ArgbColor(0xFF00FF00.toInt()),
        label: UiText = UiText.Literal("external"),
        nodeId: ExternalNodeId = ExternalNodeId.Root,
        children: List<Element> = emptyList(),
        modifier: Modifier = Modifier.Empty,
    ): ExternalElement =
        ExternalElement(
            probe = probe,
            key = key,
            width = width,
            height = height,
            color = color,
            label = label,
            nodeId = nodeId,
            children = children,
            modifier = modifier,
        )
}
