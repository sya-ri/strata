@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.FocusEvent
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.KeyCode
import dev.s7a.strata.input.KeyboardEvent
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onFocusChanged
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.node.ChildTransform
import dev.s7a.strata.node.ChildTransformNode
import dev.s7a.strata.node.ClipChildrenNode
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.PointerCaptureNode
import dev.s7a.strata.node.RootOverlayPaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.RootOverlayPaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies that retained child transforms remain coherent across core pipelines.
 */
internal class ChildTransformPipelineTest {
    @Test
    fun nestedTransformsComposePlacementScaleAndOffsetsForPaintAndSemantics() {
        val semantics = Semantics(label = UiText.Literal("nested"))
        val leafProbe = LeafProbe(semantics = semantics)
        leafProbe.paint = { scope ->
            scope.fillRectangle(IntRect(0, 0, 8, 12), CONTENT)
        }
        val leaf = LeafElement(IntSize(8, 12), leafProbe)
        val nested =
            TransformElement(
                size = IntSize(20, 20),
                childPlacement = IntOffset(4, 6),
                childTransform = ChildTransform(0.25, DoubleOffset(2.0, 4.0)),
                children = listOf(leaf),
            )
        val root =
            TransformElement(
                size = ROOT_SIZE,
                childPlacement = IntOffset(3, 5),
                childTransform = ChildTransform(0.5, DoubleOffset(1.5, 2.0)),
                children = listOf(nested),
            )
        val tree = laidOut(root)

        val commands = tree.paint()
        assertEquals(1, commands.size)
        val command = commands.single() as DrawCommand.SampledImage
        assertEquals(FloatRect(7.5f, 12f, 8.5f, 13.5f), command.destination)
        assertEquals(CONTENT, command.tint)
        assertEquals(
            listOf(SemanticsEntry(IntRect(7, 12, 9, 14), semantics)),
            tree.semantics(),
        )
        tree.close()
    }

    @Test
    fun fractionalTransformConvertsPortablePaintAndRoundsClipsOutward() {
        val source = FloatRect(0.25f, 0.5f, 1.75f, 1.5f)
        val sampledDestination = FloatRect(-0.5f, 0.25f, 2.5f, 1.25f)
        val probe = LeafProbe()
        probe.paint = { scope ->
            scope.withClip(IntRect(-1, 0, 3, 5)) {
                scope.fillRectangle(IntRect(0, 0, 4, 4), CONTENT)
                scope.blitImage(TEST_IMAGE, IntRect(0, 0, 2, 2), IntRect(1, 1, 5, 3))
                scope.sampledImage(
                    image = TEST_IMAGE,
                    source = source,
                    localDestination = sampledDestination,
                    orientation = SampledImageOrientation.FlipBoth,
                    tint = TINT,
                    alphaCutoff = 0.25f,
                )
            }
        }
        val root =
            TransformElement(
                size = ROOT_SIZE,
                childPlacement = IntOffset(1, 2),
                childTransform = ChildTransform(0.5, DoubleOffset(0.25, 0.75)),
                children = listOf(ClippedLeafElement(IntSize(6, 6), probe)),
            )
        val tree = laidOut(root)

        val commands = tree.paint()
        assertEquals(7, commands.size)
        assertEquals(DrawCommand.PushClip(IntRect(0, 2, 3, 6)), commands[0])

        val fill = commands[1] as DrawCommand.SampledImage
        assertEquals(IntSize(1, 1), fill.image.size)
        assertEquals(-1, fill.image.argbAt(0, 0))
        assertEquals(FloatRect(0f, 0f, 1f, 1f), fill.source)
        assertEquals(FloatRect(1.25f, 2.75f, 3.25f, 4.75f), fill.destination)
        assertEquals(CONTENT, fill.tint)
        assertEquals(0f, fill.alphaCutoff)
        assertEquals(SampledImageOrientation.Normal, fill.orientation)

        val blit = commands[2] as DrawCommand.SampledImage
        assertSame(TEST_IMAGE, blit.image)
        assertEquals(FloatRect(0f, 0f, 2f, 2f), blit.source)
        assertEquals(FloatRect(1.75f, 3.25f, 3.75f, 4.25f), blit.destination)
        assertEquals(ArgbColor(-1), blit.tint)
        assertEquals(0f, blit.alphaCutoff)
        assertEquals(SampledImageOrientation.Normal, blit.orientation)

        val sampled = commands[3] as DrawCommand.SampledImage
        assertSame(TEST_IMAGE, sampled.image)
        assertEquals(source, sampled.source)
        assertEquals(FloatRect(1f, 2.875f, 2.5f, 3.375f), sampled.destination)
        assertEquals(TINT, sampled.tint)
        assertEquals(0.25f, sampled.alphaCutoff)
        assertEquals(SampledImageOrientation.FlipBoth, sampled.orientation)

        assertEquals(DrawCommand.PopClip, commands[4])
        assertEquals(DrawCommand.PushClip(IntRect(1, 2, 5, 6)), commands[5])
        assertEquals(DrawCommand.PopClip, commands[6])
        tree.close()
    }

    @Test
    fun identityAndIntegerTranslationPreserveLegacyCommandVariants() {
        assertFastPathCommands(placement = null, expectedOffset = IntOffset.Zero)
        assertFastPathCommands(placement = IntOffset(3, -2), expectedOffset = IntOffset(3, -2))
    }

    @Test
    fun cleanLayoutReusesThePreviouslyResolvedChildTransform() {
        var transformReads = 0
        val root =
            TransformElement(
                size = ROOT_SIZE,
                childPlacement = IntOffset.Zero,
                childTransform = ChildTransform(0.5),
                children = listOf(LeafElement(IntSize(4, 4), LeafProbe())),
                onTransformRead = { transformReads += 1 },
            )
        val tree = laidOut(root)

        assertEquals(1, transformReads)
        tree.layout()
        assertEquals(1, transformReads)
        tree.close()
    }

    @Test
    fun inversePointerHitLocalCoordinatesAndCapturedDragDeltaUseAccumulatedScale() {
        val probe = LeafProbe()
        probe.pointerHandler = { event, _ ->
            if (event is PointerEvent.Press) InputResult.Consumed else InputResult.Ignored
        }
        val nested =
            TransformElement(
                size = IntSize(16, 16),
                childPlacement = IntOffset.Zero,
                childTransform = ChildTransform(0.5),
                children = listOf(LeafElement(IntSize(8, 8), probe)),
            )
        val root =
            TransformElement(
                size = ROOT_SIZE,
                childPlacement = IntOffset(10, 20),
                childTransform = ChildTransform(0.5, DoubleOffset(0.5, 0.5)),
                children = listOf(nested),
            )
        val tree = laidOut(root)

        assertEquals(
            InputResult.Ignored,
            tree.dispatchPointer(PointerEvent.Press(IntOffset(10, 21), PointerButton.Primary)),
        )
        assertEquals(emptyList<PointerObservation>(), probe.pointerObservations)

        val press = PointerEvent.Press(IntOffset(11, 21), PointerButton.Primary)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(press))
        assertEquals(listOf(PointerObservation(press, IntOffset(2, 2))), probe.pointerObservations)
        assertEquals(listOf(PointerButton.Primary), probe.captureAcquisitions)

        val drag = PointerEvent.Drag(IntOffset(20, 30), PointerButton.Primary, deltaX = 1.5, deltaY = -2.0)
        assertEquals(InputResult.Consumed, tree.dispatchPointer(drag))
        assertEquals(
            PointerObservation(
                PointerEvent.Drag(IntOffset(20, 30), PointerButton.Primary, deltaX = 6.0, deltaY = -8.0),
                IntOffset(38, 38),
            ),
            probe.pointerObservations.last(),
        )

        tree.dispatchPointer(PointerEvent.Release(IntOffset(20, 30), PointerButton.Primary))
        tree.close()
    }

    @Test
    fun rootOverlayRemainsInRootSpaceWhileItsAnchorUsesTransformedBounds() {
        val anchors = ArrayList<IntRect>()
        val probe = LeafProbe()
        probe.paint = { scope ->
            scope.fillRectangle(IntRect(0, 0, 4, 6), CONTENT)
        }
        probe.rootOverlay = { scope ->
            anchors.add(scope.anchorBounds)
            assertEquals(ROOT_SIZE, scope.size)
            scope.fillRectangle(ROOT_OVERLAY_BOUNDS, ROOT_OVERLAY)
        }
        val root =
            TransformElement(
                size = ROOT_SIZE,
                childPlacement = IntOffset(2, 3),
                childTransform = ChildTransform(0.5, DoubleOffset(0.5, 0.5)),
                children = listOf(LeafElement(IntSize(4, 6), probe)),
            )
        val tree = laidOut(root)

        val commands = tree.paint()
        assertEquals(listOf(IntRect(2, 3, 5, 7)), anchors)
        assertEquals(2, commands.size)
        assertEquals(FloatRect(2.5f, 3.5f, 4.5f, 6.5f), (commands[0] as DrawCommand.SampledImage).destination)
        assertEquals(DrawCommand.FillRectangle(ROOT_OVERLAY_BOUNDS, ROOT_OVERLAY), commands[1])
        tree.close()
    }

    @Test
    fun platformCommandsRejectScaledAndFractionallyTranslatedTransforms() {
        listOf(
            ChildTransform(0.5),
            ChildTransform(1.0, DoubleOffset(0.5, 0.0)),
        ).forEach { transform ->
            val probe = LeafProbe()
            probe.paint = { scope -> scope.drawPlatform(TEST_PLATFORM_COMMAND, IntRect(0, 0, 2, 2)) }
            val root =
                TransformElement(
                    size = ROOT_SIZE,
                    childPlacement = IntOffset.Zero,
                    childTransform = transform,
                    children = listOf(LeafElement(IntSize(2, 2), probe)),
                )
            val tree = laidOut(root)

            val failure = assertThrows(UnsupportedOperationException::class.java) { tree.paint() }
            assertEquals(
                "Platform draw commands require an exact integer-translation child transform.",
                failure.message,
            )
            assertEquals(TreeState.Poisoned, tree.state)
            tree.close()
        }
    }

    @Test
    fun nonemptyPortableDrawingFailsWhenFloatPrecisionCannotRepresentItsExtent() {
        val probe = LeafProbe()
        probe.paint = { scope -> scope.fillRectangle(IntRect(0, 0, 1, 1), CONTENT) }
        val root =
            TransformElement(
                size = ROOT_SIZE,
                childPlacement = IntOffset.Zero,
                childTransform = ChildTransform(1.0, DoubleOffset(2_000_000_000.5, 0.5)),
                children = listOf(LeafElement(IntSize(1, 1), probe)),
            )
        val tree = laidOut(root)

        val failure = assertThrows(IllegalArgumentException::class.java) { tree.paint() }
        assertEquals(
            "Transformed horizontal drawing extent must be representable as a Float.",
            failure.message,
        )
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    @Test
    fun nonemptyBoundsFailDuringLayoutWhenDoublePrecisionCannotRepresentTheirExtent() {
        val root =
            TransformElement(
                size = ROOT_SIZE,
                childPlacement = IntOffset.Zero,
                childTransform = ChildTransform(1e-10, DoubleOffset(2_000_000_000.0, 0.5)),
                children = listOf(LeafElement(IntSize(1, 1), LeafProbe())),
            )
        val tree = UiTree()
        tree.update(root)
        tree.measure(Constraints.fixed(ROOT_SIZE.width, ROOT_SIZE.height))

        val failure = assertThrows(IllegalArgumentException::class.java) { tree.layout() }
        assertEquals(
            "Transformed horizontal bounds extent must be representable as a Double.",
            failure.message,
        )
        assertEquals(TreeState.Poisoned, tree.state)
        tree.close()
    }

    @Test
    fun traversalSkipsAClippedTransformedTargetAndSelectsAVisibleSibling() {
        val clippedTransitions = ArrayList<FocusEvent>()
        val clippedChild =
            evaluateComponentTree {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(10, 5)
                            .scaleToFit(IntSize(4, 5), contentAlignment = Alignment.CenterEnd)
                            .onFocusChanged(clippedTransitions::add),
                )
            }
        val visibleTransitions = ArrayList<FocusEvent>()
        val visibleChild =
            evaluateComponentTree {
                Spacer(
                    modifier =
                        Modifier.Empty
                            .size(4, 5)
                            .onFocusChanged(visibleTransitions::add),
                )
            }
        val root =
            ClippedTransformElement(
                size = IntSize(5, 5),
                childPlacement = IntOffset.Zero,
                childTransform = ChildTransform.Identity,
                children = listOf(clippedChild, visibleChild),
            )
        val tree = laidOut(root, IntSize(5, 5))

        assertEquals(
            InputResult.Consumed,
            tree.dispatchKeyboard(KeyboardEvent.Press(KeyCode.Tab, 0)),
        )
        assertEquals(emptyList<FocusEvent>(), clippedTransitions)
        assertEquals(listOf(FocusEvent.Gained), visibleTransitions)
        tree.close()
    }

    private fun assertFastPathCommands(
        placement: IntOffset?,
        expectedOffset: IntOffset,
    ) {
        val source = FloatRect(0f, 0f, 2f, 2f)
        val destination = FloatRect(0.25f, 0.5f, 2.25f, 2.5f)
        val clip = IntRect(0, 0, 4, 4)
        val fill = IntRect(-1, 0, 3, 4)
        val blit = IntRect(0, 0, 4, 4)
        val platform = IntRect(1, 1, 3, 3)
        val probe = LeafProbe()
        probe.paint = { scope ->
            scope.withClip(clip) {
                scope.fillRectangle(fill, CONTENT)
                scope.blitImage(TEST_IMAGE, IntRect(0, 0, 2, 2), blit)
                scope.sampledImage(
                    TEST_IMAGE,
                    source,
                    destination,
                    SampledImageOrientation.FlipBoth,
                    TINT,
                    0.25f,
                )
                scope.drawPlatform(TEST_PLATFORM_COMMAND, platform)
            }
        }
        val leaf = LeafElement(IntSize(4, 4), probe)
        val root =
            if (placement == null) {
                leaf
            } else {
                TransformElement(
                    size = ROOT_SIZE,
                    childPlacement = placement,
                    childTransform = ChildTransform.Identity,
                    children = listOf(leaf),
                )
            }
        val tree = laidOut(root, if (placement == null) IntSize(4, 4) else ROOT_SIZE)

        assertEquals(
            listOf(
                DrawCommand.PushClip(clip + expectedOffset),
                DrawCommand.FillRectangle(fill + expectedOffset, CONTENT),
                DrawCommand.BlitImage(TEST_IMAGE, IntRect(0, 0, 2, 2), blit + expectedOffset),
                DrawCommand.SampledImage(
                    TEST_IMAGE,
                    source,
                    destination + expectedOffset,
                    TINT,
                    0.25f,
                    SampledImageOrientation.FlipBoth,
                ),
                DrawCommand.Platform(TEST_PLATFORM_COMMAND, platform + expectedOffset),
                DrawCommand.PopClip,
            ),
            tree.paint(),
        )
        tree.close()
    }

    private fun laidOut(
        element: Element,
        size: IntSize = ROOT_SIZE,
    ): UiTree =
        UiTree().also { tree ->
            tree.update(element)
            tree.measure(Constraints.fixed(size.width, size.height))
            tree.layout()
        }

    private class TransformElement(
        val size: IntSize,
        val childPlacement: IntOffset,
        val childTransform: ChildTransform,
        children: List<Element>,
        val onTransformRead: () -> Unit = { },
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<TransformElement, TransformNode> =
                ElementType(
                    elementClass = TransformElement::class,
                    nodeClass = TransformNode::class,
                    validateLocal = { },
                    createNode = { element ->
                        TransformNode(element.size, element.childPlacement, element.childTransform, element.onTransformRead)
                    },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private open class TransformNode(
        private val size: IntSize,
        private val childPlacement: IntOffset,
        private val transform: ChildTransform,
        private val onTransformRead: () -> Unit,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        ChildTransformNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            for (index in 0 until scope.childCount) scope.measureChild(index, Constraints())
            return constraints.constrain(size)
        }

        override fun layout(scope: LayoutScope) {
            for (index in 0 until scope.childCount) scope.placeChild(index, childPlacement)
        }

        override fun childTransform(index: Int): ChildTransform {
            require(0 <= index)
            onTransformRead()
            return transform
        }
    }

    private class ClippedTransformElement(
        val size: IntSize,
        val childPlacement: IntOffset,
        val childTransform: ChildTransform,
        children: List<Element>,
    ) : Element(ElementIdentity.Positional, TYPE, children) {
        companion object {
            val TYPE: ElementType<ClippedTransformElement, ClippedTransformNode> =
                ElementType(
                    elementClass = ClippedTransformElement::class,
                    nodeClass = ClippedTransformNode::class,
                    validateLocal = { },
                    createNode = { element ->
                        ClippedTransformNode(element.size, element.childPlacement, element.childTransform)
                    },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class ClippedTransformNode(
        size: IntSize,
        childPlacement: IntOffset,
        childTransform: ChildTransform,
    ) : TransformNode(size, childPlacement, childTransform, { }),
        ClipChildrenNode

    private class LeafElement(
        val size: IntSize,
        val probe: LeafProbe,
    ) : Element(ElementIdentity.Positional, TYPE) {
        companion object {
            val TYPE: ElementType<LeafElement, LeafNode> =
                ElementType(
                    elementClass = LeafElement::class,
                    nodeClass = LeafNode::class,
                    validateLocal = { },
                    createNode = { element -> LeafNode(element.size, element.probe) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class ClippedLeafElement(
        val size: IntSize,
        val probe: LeafProbe,
    ) : Element(ElementIdentity.Positional, TYPE) {
        companion object {
            val TYPE: ElementType<ClippedLeafElement, ClippedLeafNode> =
                ElementType(
                    elementClass = ClippedLeafElement::class,
                    nodeClass = ClippedLeafNode::class,
                    validateLocal = { },
                    createNode = { element -> ClippedLeafNode(element.size, element.probe) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private open class LeafNode(
        private val size: IntSize,
        private val probe: LeafProbe,
    ) : Node(),
        MeasureNode,
        PaintNode,
        RootOverlayPaintNode,
        SemanticsNode,
        PointerCaptureNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            require(scope.childCount == 0)
            require(constraints.isSatisfiedBy(size))
            return size
        }

        override fun paint(scope: PaintScope) {
            probe.paint(scope)
        }

        override fun paintRootOverlay(scope: RootOverlayPaintScope) {
            probe.rootOverlay(scope)
        }

        override fun semantics(scope: SemanticsScope) {
            probe.semantics?.let(scope::emit)
        }

        override fun onPointerEvent(
            event: PointerEvent,
            localPosition: IntOffset,
        ): InputResult {
            probe.pointerObservations.add(PointerObservation(event, localPosition))
            return probe.pointerHandler(event, localPosition)
        }

        override fun onPointerCaptureAcquired(button: PointerButton) {
            probe.captureAcquisitions.add(button)
        }

        override fun onPointerCaptureCancelled(button: PointerButton) {
            probe.captureCancellations.add(button)
        }
    }

    private class ClippedLeafNode(
        size: IntSize,
        probe: LeafProbe,
    ) : LeafNode(size, probe),
        ClipChildrenNode

    private class LeafProbe(
        val semantics: Semantics? = null,
    ) {
        var paint: (PaintScope) -> Unit = { }
        var rootOverlay: (RootOverlayPaintScope) -> Unit = { }
        var pointerHandler: (PointerEvent, IntOffset) -> InputResult = { _, _ -> InputResult.Ignored }
        val pointerObservations: MutableList<PointerObservation> = ArrayList()
        val captureAcquisitions: MutableList<PointerButton> = ArrayList()
        val captureCancellations: MutableList<PointerButton> = ArrayList()
    }

    private data class PointerObservation(
        val event: PointerEvent,
        val localPosition: IntOffset,
    )

    private data object TestPlatformCommand : PlatformDrawCommand

    private companion object {
        val ROOT_SIZE: IntSize = IntSize(64, 64)
        val CONTENT: ArgbColor = ArgbColor(0xFF336699.toInt())
        val TINT: ArgbColor = ArgbColor(0x80CC8844.toInt())
        val ROOT_OVERLAY: ArgbColor = ArgbColor(0xFFAA5500.toInt())
        val ROOT_OVERLAY_BOUNDS: IntRect = IntRect(20, 30, 22, 32)
        val TEST_IMAGE =
            createDrawImage(
                IntSize(2, 2),
                intArrayOf(
                    0xFF000000.toInt(),
                    0xFFFFFFFF.toInt(),
                    0xFFFF0000.toInt(),
                    0xFF00FF00.toInt(),
                ),
            )
        val TEST_PLATFORM_COMMAND: PlatformDrawCommand = TestPlatformCommand
    }
}
