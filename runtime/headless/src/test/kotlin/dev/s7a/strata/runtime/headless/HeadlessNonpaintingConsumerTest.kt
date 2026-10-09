package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.canvasSource
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.node.SemanticsNode
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.semantics.SemanticsEntry
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.semantics.Semantics
import dev.s7a.strata.semantics.SemanticsScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Exercises nonpainting commands inside a complete retained consumer, including callback and source-cutoff ordering.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class HeadlessNonpaintingConsumerTest {
    @Test
    @Suppress("LongMethod") // Keep the subscribe, paint-time update, input and next-frame cutoff assertions in one consumer lifetime.
    fun invisiblePaintingKeepsCallbacksSemanticsInputAndWholeFrameSourceCutoff() {
        val red = createDrawImage(IntSize(1, 1), intArrayOf(0xFFFF0000.toInt()))
        val blue = createDrawImage(IntSize(1, 1), intArrayOf(0xFF0000FF.toInt()))
        var observer: (StateSnapshot<DrawImage>) -> Unit = { error("The canvas has not subscribed.") }
        var closes = 0
        var paints = 0
        val events = ArrayList<PointerEvent>()
        val source =
            canvasSource(
                StateSource<DrawImage> {
                    observer = it
                    StateSubscription(StateSnapshot(StateRevision(0), red)) { closes += 1 }
                },
            )
        val description =
            evaluateComponentTree {
                Stack {
                    element(
                        InvisibleElement(
                            red,
                            onPaint = {
                                paints += 1
                                if (paints == 1) observer(StateSnapshot(StateRevision(1), blue))
                            },
                            modifier =
                                Modifier.Empty.onPointerEvent { event, _ ->
                                    events.add(event)
                                    InputResult.Consumed
                                },
                        ),
                    )
                    Canvas(source, IntSize(4, 4))
                }
            }
        val bounds = IntRect(0, 0, 4, 4)
        createRuntimeUiSession { description }.use { session ->
            session.attach()
            val first = session.frame(Constraints.fixed(4, 4))
            val originalCommands = first.drawCommands.toList()
            val expectedSemantics = listOf(SemanticsEntry(bounds, Semantics(label = UiText.Literal("invisible"))))
            assertEquals(expectedSemantics, first.semantics)
            assertEquals(1, paints)
            assertSame(
                red,
                first.drawCommands
                    .filterIsInstance<DrawCommand.BlitImage>()
                    .last()
                    .image,
            )
            for (scale in 1..4) {
                val image = rasterizeHeadless(first.drawCommands, first.size, scale)
                assertArrayEquals(HeadlessScalarRaster.paint(first.drawCommands, bounds, scale), image.copyArgb())
                assertArrayEquals(IntArray(16 * scale * scale) { 0xFFFF0000.toInt() }, image.copyArgb())
            }
            val press = PointerEvent.Press(IntOffset(1, 1), PointerButton.Primary)
            assertEquals(InputResult.Consumed, session.dispatchPointer(press))
            assertEquals(listOf(press), events)
            assertEquals(1, paints)
            assertEquals(originalCommands, first.drawCommands)
            assertEquals(expectedSemantics, first.semantics)
            val second = session.frame(Constraints.fixed(4, 4))
            assertSame(
                blue,
                second.drawCommands
                    .filterIsInstance<DrawCommand.BlitImage>()
                    .last()
                    .image,
            )
            assertEquals(expectedSemantics, second.semantics)
            assertArrayEquals(HeadlessScalarRaster.paint(second.drawCommands, bounds, 1), rasterizeHeadless(second.drawCommands, second.size).copyArgb())
            assertSame(
                red,
                first.drawCommands
                    .filterIsInstance<DrawCommand.BlitImage>()
                    .last()
                    .image,
            )
        }
        assertEquals(1, closes)
        assertArrayEquals(intArrayOf(0xFFFF0000.toInt()), red.copyArgb())
    }

    /**
     * Downstream-style public primitive whose valid paint commands have no visible effect.
     */
    private class InvisibleElement(
        val image: DrawImage,
        val onPaint: () -> Unit,
        modifier: Modifier,
    ) : Element(ElementIdentity.Positional, TYPE, modifier = modifier) {
        companion object {
            val TYPE: ElementType<InvisibleElement, Retained> =
                ElementType(
                    InvisibleElement::class,
                    Retained::class,
                    validateLocal = { require(it.image.size == IntSize(1, 1)) },
                    createNode = { Retained(it.image, it.onPaint) },
                    updateNode = { previous, current, _ ->
                        check(previous === current)
                        DirtyMask.None
                    },
                )
        }
    }

    /**
     * Emits all callbacks and commands through the public SPI; clipping does not suppress logical semantics.
     */
    private class Retained(
        private val image: DrawImage,
        private val onPaint: () -> Unit,
    ) :
        Node(),
        MeasureNode,
        PaintNode,
        SemanticsNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(4, 4))

        override fun paint(scope: PaintScope) {
            onPaint()
            scope.fillRectangle(IntRect(4, 0, 8, 4), ArgbColor(-1))
            scope.withClip(IntRect(0, 0, 0, 4)) {
                scope.blitImage(image, IntRect(0, 0, 1, 1), IntRect(0, 0, 4, 4))
            }
            scope.sampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 4f, 4f), ArgbColor(0x001337AA), 0f)
        }

        override fun semantics(scope: SemanticsScope) {
            scope.emit(Semantics(label = UiText.Literal("invisible")))
        }
    }
}
