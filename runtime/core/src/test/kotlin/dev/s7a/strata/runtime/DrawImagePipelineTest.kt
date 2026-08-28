package dev.s7a.strata.runtime

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.DirtyPhase
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Verifies image command validation and local paint retention in core.
 */
internal class DrawImagePipelineTest {
    @Test
    fun sampledCommandsRejectInvalidSourcesDestinationsAndCutoffs() {
        val image = image()
        val valid = FloatRect(0f, 0f, 2f, 2f)
        listOf(
            FloatRect(-0.25f, 0f, 1f, 1f),
            FloatRect(0f, -0.25f, 1f, 1f),
            FloatRect(0f, 0f, 2.25f, 1f),
            FloatRect(0f, 0f, 1f, 2.25f),
            FloatRect(0f, 0f, 0f, 1f),
            FloatRect(0f, 0f, 1f, 0f),
        ).forEach { invalid ->
            assertThrows<IllegalArgumentException> { DrawCommand.SampledImage(image, invalid, valid) }
        }
        listOf(FloatRect(0f, 0f, 0f, 1f), FloatRect(0f, 0f, 1f, 0f)).forEach { invalid ->
            assertThrows<IllegalArgumentException> { DrawCommand.SampledImage(image, valid, invalid) }
        }
        listOf(-0.1f, 1.1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            assertThrows<IllegalArgumentException> { DrawCommand.SampledImage(image, valid, valid, alphaCutoff = invalid) }
        }
        val empty = createDrawImage(IntSize(0, 0), intArrayOf())
        assertThrows<IllegalArgumentException> { DrawCommand.SampledImage(empty, valid, valid) }
    }

    @Test
    fun sampledPaintPreservesFractionalTranslationOrderRetentionAndScopeLifetime() {
        val image = image()
        val source = FloatRect(0.25f, 0.5f, 1.75f, 2f)
        val destination = FloatRect(-0.25f, 1.5f, 2.75f, 4.5f)
        val tint = ArgbColor(0x80AABBCC.toInt())
        var retainedScope: PaintScope? = null
        val child =
            BlitElement(
                image,
                IntRect(0, 0, 2, 2),
                IntRect(0, 0, 2, 2),
                before = ArgbColor(0xFF101010.toInt()),
                after = ArgbColor(0xFF202020.toInt()),
                customPaint = { scope ->
                    retainedScope = scope
                    scope.sampledImage(image, source, destination, SampledImageOrientation.FlipBoth, tint, alphaCutoff = 0.25f)
                },
            )
        val tree = UiTree()
        tree.update(ParentElement(child))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val commands = tree.paint()
        assertEquals(4, commands.size)
        val sampled = commands[2] as DrawCommand.SampledImage
        assertSame(image, sampled.image)
        assertEquals(source, sampled.source)
        assertEquals(FloatRect(2.75f, 5.5f, 5.75f, 8.5f), sampled.destination)
        assertEquals(tint, sampled.tint)
        assertEquals(0.25f, sampled.alphaCutoff)
        assertEquals(SampledImageOrientation.FlipBoth, sampled.orientation)
        assertEquals(ArgbColor(0xFF101010.toInt()), (commands[1] as DrawCommand.FillRectangle).color)
        assertEquals(ArgbColor(0xFF202020.toInt()), (commands[3] as DrawCommand.FillRectangle).color)
        assertEquals(commands, tree.paint())
        assertEquals(1, child.node.paintCalls)
        child.node.invalidatePaint()
        assertEquals(commands, tree.paint())
        assertEquals(2, child.node.paintCalls)
        assertThrows<IllegalStateException> {
            requireNotNull(retainedScope).sampledImage(image, source, destination, SampledImageOrientation.FlipBoth)
        }
        tree.close()
    }

    @Test
    fun sampledPaintScopeValidatesBeforeRetainingACommand() {
        val image = image()
        val source = FloatRect(0f, -0.5f, 1f, 1f)
        val destination = FloatRect(0f, 0f, 1f, 1f)
        val tree = UiTree()
        tree.update(
            BlitElement(image, IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 1), customPaint = { scope ->
                assertThrows<IllegalArgumentException> { scope.sampledImage(image, source, destination) }
                scope.sampledImage(image, destination, destination)
            }),
        )
        tree.measure(Constraints.fixed(8, 8))
        tree.layout()
        assertEquals(listOf(DrawCommand.SampledImage(image, destination, destination)), tree.paint())
        tree.close()
    }

    @Test
    fun publicCommandChecksEverySourceEdgeAndDestinationExtent() {
        val image = image()
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(-1, 0, 1, 1), IntRect(0, 0, 1, 1))
        }
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(0, -1, 1, 1), IntRect(0, 0, 1, 1))
        }
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(1, 0, 3, 1), IntRect(0, 0, 1, 1))
        }
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(0, 1, 1, 3), IntRect(0, 0, 1, 1))
        }
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(0, 0, 0, 1), IntRect(0, 0, 1, 1))
        }
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(0, 0, 1, 0), IntRect(0, 0, 1, 1))
        }
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(0, 0, 1, 1), IntRect(0, 0, 1, 0))
        }
        assertThrows<IllegalArgumentException> {
            DrawCommand.BlitImage(image, IntRect(0, 0, 1, 1), IntRect(0, 0, 0, 1))
        }
    }

    @Test
    fun paintScopeRetainsValidCommandAndRejectsOutOfBoundsSource() {
        val image = image()
        val tree = UiTree()
        tree.update(BlitElement(image, IntRect(0, 0, 2, 2), IntRect(1, 2, 4, 6)))
        tree.measure(Constraints.fixed(8, 8))
        tree.layout()

        val command = tree.paint().single() as DrawCommand.BlitImage
        assertSame(image, command.image)
        assertEquals(IntRect(0, 0, 2, 2), command.source)
        assertEquals(IntRect(1, 2, 4, 6), command.destination)
        tree.close()

        val invalidTree = UiTree()
        invalidTree.update(BlitElement(image, IntRect(0, -1, 1, 1), IntRect(0, 0, 1, 1)))
        invalidTree.measure(Constraints.fixed(8, 8))
        invalidTree.layout()
        assertThrows<IllegalArgumentException> { invalidTree.paint() }
        invalidTree.close()

        val bottomInvalidTree = UiTree()
        bottomInvalidTree.update(BlitElement(image, IntRect(0, 1, 1, 3), IntRect(0, 0, 1, 1)))
        bottomInvalidTree.measure(Constraints.fixed(8, 8))
        bottomInvalidTree.layout()
        assertThrows<IllegalArgumentException> { bottomInvalidTree.paint() }
        bottomInvalidTree.close()
    }

    @Test
    fun treeTranslationOrderCachingAndPaintInvalidationAreRetained() {
        val image = image()
        val child =
            BlitElement(
                image,
                IntRect(0, 0, 2, 2),
                IntRect(1, 2, 4, 6),
                ArgbColor(0xFF101010.toInt()),
                ArgbColor(0xFF202020.toInt()),
            )
        val tree = UiTree()
        tree.update(ParentElement(child))
        tree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        tree.layout()
        val first = tree.paint()
        assertEquals(4, first.size)
        assertEquals(DrawCommand.FillRectangle(IntRect(0, 0, 12, 12), ArgbColor(0xFF303030.toInt())), first[0])
        assertEquals(DrawCommand.FillRectangle(IntRect(3, 4, 11, 12), ArgbColor(0xFF101010.toInt())), first[1])
        val blit = first[2] as DrawCommand.BlitImage
        assertSame(image, blit.image)
        assertEquals(IntRect(0, 0, 2, 2), blit.source)
        assertEquals(IntRect(4, 6, 7, 10), blit.destination)
        assertEquals(DrawCommand.FillRectangle(IntRect(3, 4, 11, 12), ArgbColor(0xFF202020.toInt())), first[3])
        val paintsBeforeCache = child.node.paintCalls
        tree.paint()
        assertEquals(paintsBeforeCache, child.node.paintCalls)
        child.node.invalidatePaint()
        tree.paint()
        assertEquals(paintsBeforeCache + 1, child.node.paintCalls)
        tree.close()

        val overflowTree = UiTree()
        overflowTree.update(
            ParentElement(
                BlitElement(image, IntRect(0, 0, 1, 1), IntRect(Int.MAX_VALUE - 1, 0, Int.MAX_VALUE, 1)),
                IntOffset(1, 0),
            ),
        )
        overflowTree.measure(Constraints(maxWidth = 20, maxHeight = 20))
        overflowTree.layout()
        assertThrows<ArithmeticException> { overflowTree.paint() }
        overflowTree.close()
    }

    private fun image(): DrawImage =
        createDrawImage(
            IntSize(2, 2),
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt()),
        )

    private class BlitElement(
        private val image: DrawImage,
        private val source: IntRect,
        private val destination: IntRect,
        private val before: ArgbColor? = null,
        private val after: ArgbColor? = null,
        private val customPaint: ((PaintScope) -> Unit)? = null,
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
        ) {
        private lateinit var retainedNode: BlitNode

        companion object {
            val TYPE: ElementType<BlitElement, BlitNode> =
                ElementType(
                    elementClass = BlitElement::class,
                    nodeClass = BlitNode::class,
                    validateLocal = { },
                    createNode = { element ->
                        BlitNode(element.image, element.source, element.destination, element.before, element.after, element.customPaint).also {
                            element.retainedNode = it
                        }
                    },
                    updateNode = { _, element, node ->
                        node.image = element.image
                        node.source = element.source
                        node.destination = element.destination
                        node.before = element.before
                        node.after = element.after
                        node.customPaint = element.customPaint
                        DirtyMask.of(DirtyPhase.Paint)
                    },
                )
        }

        val node: BlitNode
            get() = retainedNode
    }

    private class BlitNode(
        var image: DrawImage,
        var source: IntRect,
        var destination: IntRect,
        var before: ArgbColor?,
        var after: ArgbColor?,
        var customPaint: ((PaintScope) -> Unit)?,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode {
        var paintCalls: Int = 0

        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(8, 8))

        override fun layout(scope: LayoutScope) = Unit

        override fun paint(scope: PaintScope) {
            paintCalls += 1
            before?.let { color -> scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color) }
            val paint = customPaint
            if (paint == null) {
                scope.blitImage(image, source, destination)
            } else {
                paint(scope)
            }
            after?.let { color -> scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), color) }
        }

        fun invalidatePaint() {
            invalidate(DirtyMask.of(DirtyPhase.Paint))
        }
    }

    private class ParentElement(
        child: Element,
        private val offset: IntOffset = IntOffset(3, 4),
    ) : Element(
            identity = ElementIdentity.Positional,
            type = TYPE,
            children = listOf(child),
        ) {
        companion object {
            val TYPE: ElementType<ParentElement, ParentNode> =
                ElementType(
                    elementClass = ParentElement::class,
                    nodeClass = ParentNode::class,
                    validateLocal = { },
                    createNode = { element -> ParentNode(element.offset) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    private class ParentNode(
        private val offset: IntOffset,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize {
            scope.measureChild(0, constraints)
            return constraints.constrain(IntSize(12, 12))
        }

        override fun layout(scope: LayoutScope) {
            scope.placeChild(0, offset)
        }

        override fun paint(scope: PaintScope) {
            scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), ArgbColor(0xFF303030.toInt()))
        }
    }
}
