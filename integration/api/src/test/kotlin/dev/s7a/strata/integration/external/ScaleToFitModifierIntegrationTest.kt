package dev.s7a.strata.integration.external

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.input.PointerHoverEvent
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.onHover
import dev.s7a.strata.modifier.onPress
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies scale-to-fit measurement, retained updates, transformed semantics, and inverse pointer coordinates through public API use.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class ScaleToFitModifierIntegrationTest {
    @Test
    fun fixedContentConstraintsAndNaturalOuterSizeRemainIndependent() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(element(probe, Modifier.Empty.scaleToFit(IntSize(100, 50))))

        assertEquals(
            IntSize(100, 50),
            tree.measure(Constraints(maxWidth = 200, maxHeight = 100)),
        )
        assertEquals(
            IntSize(80, 50),
            tree.measure(Constraints(maxWidth = 80, maxHeight = Int.MAX_VALUE)),
        )
        assertEquals(Constraints.fixed(100, 50), probe.componentMeasureConstraints.single())
        tree.close()
    }

    @Test
    fun containAlignmentDefaultsToCenteredWithoutUpscalingAndSupportsExplicitEndAlignment() {
        val centeredProbe = ExternalProbe()
        val centeredTree = UiTree()
        centeredTree.update(element(centeredProbe, Modifier.Empty.scaleToFit(IntSize(100, 100))))
        centeredTree.measure(Constraints.fixed(300, 200))
        centeredTree.layout()
        assertEquals(IntRect(100, 50, 200, 150), centeredTree.semantics().single().bounds)
        centeredTree.close()

        val endProbe = ExternalProbe()
        val endTree = UiTree()
        endTree.update(
            element(
                endProbe,
                Modifier.Empty.scaleToFit(
                    contentSize = IntSize(100, 100),
                    contentAlignment = Alignment.BottomEnd,
                ),
            ),
        )
        endTree.measure(Constraints.fixed(120, 80))
        endTree.layout()
        assertEquals(IntRect(40, 0, 120, 80), endTree.semantics().single().bounds)
        endTree.close()
    }

    @Test
    fun optInUpscalingContainsContentAndPublishesTransformedSemantics() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(
            element(
                probe,
                Modifier.Empty.scaleToFit(
                    contentSize = IntSize(100, 100),
                    allowUpscaling = true,
                ),
            ),
        )

        tree.measure(Constraints.fixed(300, 200))
        tree.layout()

        assertEquals(IntRect(50, 0, 250, 200), tree.semantics().single().bounds)
        tree.close()
    }

    @Test
    fun zeroOuterAxisMeasuresButDoesNotPlaceTheContentSubtree() {
        val probe = ExternalProbe()
        val tree = UiTree()
        tree.update(element(probe, Modifier.Empty.scaleToFit(IntSize(100, 50))))

        assertEquals(IntSize(0, 10), tree.measure(Constraints.fixed(0, 10)))
        tree.layout()

        assertEquals(Constraints.fixed(100, 50), probe.componentMeasureConstraints.single())
        assertEquals(0, probe.componentNodes.getValue(ExternalNodeId.Root).layouts)
        assertEquals(emptyList<Any>(), tree.paint())
        assertEquals(emptyList<Any>(), tree.semantics())
        assertEquals(
            InputResult.Ignored,
            tree.dispatchPointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary)),
        )
        tree.close()
    }

    @Test
    fun retainedPolicyUpdatesInvalidateOnlyRequiredGeometryPhase() {
        val probe = ExternalProbe()
        val tree = UiTree()
        val key = ElementKey<String>("scale")
        tree.update(element(probe, Modifier.Empty.scaleToFit(IntSize(100, 50)), key))
        tree.measure(Constraints.fixed(300, 200))
        tree.layout()
        val component = probe.componentNodes.getValue(ExternalNodeId.Root)
        val initialMeasures = component.measures
        val initialLayouts = component.layouts

        tree.update(
            element(
                probe,
                Modifier.Empty.scaleToFit(IntSize(100, 50), contentAlignment = Alignment.TopStart),
                key,
            ),
        )
        tree.measure(Constraints.fixed(300, 200))
        tree.layout()
        assertSame(component, probe.componentNodes.getValue(ExternalNodeId.Root))
        assertEquals(initialMeasures, component.measures)
        assertEquals(initialLayouts, component.layouts)
        assertEquals(IntRect(0, 0, 100, 50), tree.semantics().single().bounds)

        tree.update(
            element(
                probe,
                Modifier.Empty.scaleToFit(
                    IntSize(100, 50),
                    contentAlignment = Alignment.TopStart,
                    allowUpscaling = true,
                ),
                key,
            ),
        )
        tree.measure(Constraints.fixed(300, 200))
        tree.layout()
        assertSame(component, probe.componentNodes.getValue(ExternalNodeId.Root))
        assertEquals(initialMeasures, component.measures)
        assertEquals(initialLayouts, component.layouts)
        assertEquals(IntRect(0, 0, 300, 150), tree.semantics().single().bounds)

        tree.update(
            element(
                probe,
                Modifier.Empty.scaleToFit(
                    IntSize(120, 60),
                    contentAlignment = Alignment.TopStart,
                    allowUpscaling = true,
                ),
                key,
            ),
        )
        tree.measure(Constraints.fixed(300, 200))
        tree.layout()
        assertSame(component, probe.componentNodes.getValue(ExternalNodeId.Root))
        assertEquals(initialMeasures + 1, component.measures)
        assertEquals(Constraints.fixed(120, 60), probe.componentMeasureConstraints.last())
        tree.close()
    }

    @Test
    fun pointerHitTestingUsesInverseTransformedLocalCoordinates() {
        val positions = ArrayList<IntOffset>()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .scaleToFit(IntSize(100, 100), allowUpscaling = true)
                            .onPress { _, position ->
                                positions += position
                                InputResult.Consumed
                            },
                )
            },
        )
        tree.measure(Constraints.fixed(300, 200))
        tree.layout()

        assertEquals(
            InputResult.Ignored,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(49, 40), PointerButton.Primary)),
        )
        assertEquals(
            InputResult.Consumed,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(76, 44), PointerButton.Primary)),
        )
        assertEquals(listOf(IntOffset(13, 22)), positions)
        tree.close()
    }

    @Test
    fun fractionalLetterboxEdgesDoNotReceiveHoverOrPointerFocus() {
        val hoverEvents = ArrayList<PointerHoverEvent>()
        val focusEvents = ArrayList<FocusEvent>()
        val tree = UiTree()
        tree.update(
            evaluateComponentTree {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .scaleToFit(IntSize(3, 3))
                            .onHover(hoverEvents::add)
                            .onFocusChanged(focusEvents::add),
                )
            },
        )
        tree.measure(Constraints.fixed(4, 4))
        tree.layout()

        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(0, 1))))
        assertEquals(
            InputResult.Ignored,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(0, 1), PointerButton.Primary)),
        )
        assertEquals(emptyList<PointerHoverEvent>(), hoverEvents)
        assertEquals(emptyList<FocusEvent>(), focusEvents)

        assertEquals(InputResult.Ignored, tree.dispatchPointer(PointerEvent.Move(IntOffset(1, 1))))
        assertEquals(
            InputResult.Ignored,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)),
        )
        assertEquals(listOf(PointerHoverEvent.Enter), hoverEvents)
        assertEquals(listOf(FocusEvent.Gained), focusEvents)
        tree.close()
    }

    private fun element(
        probe: ExternalProbe,
        modifier: Modifier,
        key: ElementKey<*> = ElementKey("root"),
    ): ExternalElement =
        ExternalElement(
            probe = probe,
            key = key,
            modifier = modifier,
        )
}
