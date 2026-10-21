package dev.s7a.strata.runtime.web

import dev.s7a.strata.element.Element
import dev.s7a.strata.element.ElementIdentity
import dev.s7a.strata.element.ElementType
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.LayoutScope
import dev.s7a.strata.layout.MeasureScope
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.node.DirtyMask
import dev.s7a.strata.node.LayoutNode
import dev.s7a.strata.node.MeasureNode
import dev.s7a.strata.node.Node
import dev.s7a.strata.node.PaintNode
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Exercises every portable image command through retained frames and the actual DOM renderer at high zoom.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class WebImageViewportTest {
    @Test
    fun highZoomImagesAllocateOnlyTheirNestedVisibleClip() {
        for (density in 1..3) {
            withDensity(density) {
                Kind.entries.forEach { kind -> render(kind, clipped = true, density) }
            }
        }
    }

    @Test
    fun viewportBoundsImagesEvenWithoutAnExplicitClip() {
        for (density in 1..3) {
            withDensity(density) {
                Kind.entries.forEach { kind -> render(kind, clipped = false, density) }
            }
        }
    }

    private fun render(
        kind: Kind,
        clipped: Boolean,
        density: Int,
    ) {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt()))
        val modifier = if (kind == Kind.Scaled) Modifier.scaleToFit(IntSize(4, 4), allowUpscaling = true) else Modifier
        val session =
            createRuntimeUiSession {
                ImageElement(modifier) { scope ->
                    val draw = {
                        if (kind == Kind.Sampled) {
                            scope.sampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(-10000f, -10000f, 10000f, 10000f))
                        } else {
                            scope.blitImage(image, IntRect(0, 0, 2, 2), IntRect(-10000, -10000, 10000, 10000))
                        }
                    }
                    if (clipped) scope.withClip(IntRect(1, 1, 3, 3), draw) else draw()
                }
            }
        val root = document.createElement("div") as HTMLElement
        val renderer = WebDomRenderer(root)
        try {
            session.attach()
            val frame = session.frame(Constraints.fixed(6, 6))
            val command = frame.drawCommands.single { it is DrawCommand.SampledImage || it is DrawCommand.BlitImage || it is DrawCommand.BlitImagePixels }
            when (kind) {
                Kind.Sampled -> assertIs<DrawCommand.SampledImage>(command)
                Kind.Logical -> assertIs<DrawCommand.BlitImage>(command)
                Kind.Scaled -> assertIs<DrawCommand.SampledImage>(command)
            }
            renderer.render(frame)
            val canvas = root.querySelector("canvas") as HTMLCanvasElement
            val extent = if (clipped) (if (kind == Kind.Scaled) 3 else 2) else 6
            assertEquals(extent * density, canvas.width)
            assertEquals(extent * density, canvas.height)
            val context = canvas.getContext("2d") as CanvasRenderingContext2D
            val pixels = context.getImageData(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble()).data
            for (index in 0 until canvas.width * canvas.height) {
                assertEquals(listOf(255, 255, 0, 255), (0..3).map { pixels[index * 4 + it].toInt() })
            }
        } finally {
            renderer.close()
            session.close()
        }
    }

    private fun withDensity(
        density: Int,
        action: () -> Unit,
    ) {
        val objects = js("Object")
        val original = objects.getOwnPropertyDescriptor(window, "devicePixelRatio")
        val property = js("({ configurable: true })")
        property.value = density
        objects.defineProperty(window, "devicePixelRatio", property)
        try {
            action()
        } finally {
            if (original == null) js("Reflect").deleteProperty(window, "devicePixelRatio") else objects.defineProperty(window, "devicePixelRatio", original)
        }
    }

    /**
     * Selects the actual retained command expected after optional fractional scaling.
     */
    private enum class Kind { Sampled, Logical, Scaled }

    /**
     * Supplies one immutable paint callback through the public retained primitive SPI.
     */
    private class ImageElement(
        modifier: Modifier,
        val draw: (PaintScope) -> Unit,
    ) : Element(ElementIdentity.Positional, TYPE, modifier = modifier) {
        companion object {
            val TYPE =
                ElementType(
                    elementClass = ImageElement::class,
                    nodeClass = ImageNode::class,
                    validateLocal = {},
                    createNode = { ImageNode(it.draw) },
                    updateNode = { _, _, _ -> DirtyMask.None },
                )
        }
    }

    /**
     * Emits portable operations from a leaf whose layout follows its fixed test constraints.
     */
    private class ImageNode(
        val draw: (PaintScope) -> Unit,
    ) : Node(),
        MeasureNode,
        LayoutNode,
        PaintNode {
        override fun measure(
            scope: MeasureScope,
            constraints: Constraints,
        ): IntSize = constraints.constrain(IntSize(4, 4))

        override fun layout(scope: LayoutScope) = Unit

        override fun paint(scope: PaintScope) = draw(scope)
    }
}
