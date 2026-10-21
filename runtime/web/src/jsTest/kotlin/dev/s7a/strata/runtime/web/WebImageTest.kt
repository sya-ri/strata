package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Verifies actual canvas pixels for prepared resources and normalized face/hat layers.
 */
internal class WebImageTest {
    @Test
    fun outputDensityPreservesTheDifferenceBetweenLogicalAndPhysicalSampling() {
        val objects = js("Object")
        val original = objects.getOwnPropertyDescriptor(window, "devicePixelRatio")
        objects.defineProperty(window, "devicePixelRatio", js("({ configurable: true, value: 2 })"))
        try {
            val image = createDrawImage(IntSize(2, 1), intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()))
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            val source = FloatRect(0f, 0f, 2f, 1f)
            val destination = FloatRect(0f, 0f, 1f, 1f)
            val viewport = IntSize(1, 1)
            WebImageRasterizer.draw(canvas, image, source, destination, viewport, null, ArgbColor(-1), 0f, SampledImageOrientation.Normal, physicalSampling = false)
            val context = canvas.getContext("2d") as CanvasRenderingContext2D
            val logical = context.getImageData(0.0, 0.0, 2.0, 1.0).data
            assertEquals(listOf(0, 255, 0, 255, 0, 255, 0, 255), (0 until 8).map { logical[it].toInt() })
            WebImageRasterizer.draw(canvas, image, source, destination, viewport, null, ArgbColor(-1), 0f, SampledImageOrientation.Normal)
            val physical = context.getImageData(0.0, 0.0, 2.0, 1.0).data
            assertEquals(listOf(255, 0, 0, 255, 0, 255, 0, 255), (0 until 8).map { physical[it].toInt() })
        } finally {
            if (original == null) js("Reflect").deleteProperty(window, "devicePixelRatio") else objects.defineProperty(window, "devicePixelRatio", original)
        }
    }

    @Test
    fun presentationFailureReleasesNewNodesAllocatedBeforeTheFailure() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val root = document.createElement("div") as HTMLElement
        assertFailsWith<IllegalArgumentException> {
            mountWeb(
                UiDefinition {
                    Stack {
                        Text("Allocated before failure")
                        Image(ImageSource.Pixels(image), IntSize(10000, 10000))
                    }
                },
                root,
                IntSize(10000, 10000),
            )
        }
        assertFalse(root.hasChildNodes())
    }

    @Test
    fun arbitraryHeadSamplingInterpolatesInsideTheFaceWithoutAtlasBleed() {
        val image =
            createDrawImage(
                IntSize(64, 64),
                IntArray(4096) { index ->
                    val x = index % 64
                    val y = index / 64
                    if (x in 8..15 && y in 8..15) 0xff000000.toInt() or (((x - 8) * 255 / 7) shl 16) else 0xff0000ff.toInt()
                },
            )
        val face = sampleWebHeadRegion(image, IntRect(8, 8, 16, 16), 13)
        assertEquals(0xff000000.toInt(), face.argbAt(0, 0))
        assertEquals(127, face.argbAt(6, 6) ushr 16 and 255)
        assertEquals(0xffff0000.toInt(), face.argbAt(12, 12))
    }

    @Test
    fun preparedResourceUsesSourcePixelsAndReleasesOwnedCanvases() {
        val id = ResourceId("test", "image.png")
        val image = createDrawImage(IntSize(2, 1), intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt()))
        val root = document.createElement("div") as HTMLElement
        val host = mountWeb(UiDefinition { Stack { Image(ImageSource.Resource(id), IntSize(4, 2)) } }, root, IntSize(10, 10), WebUiResources(mapOf(id to image)))
        val canvas = root.querySelector("canvas") as HTMLCanvasElement
        val context = canvas.getContext("2d") as CanvasRenderingContext2D
        val pixels = context.getImageData(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble()).data
        assertEquals(255, pixels[0].toInt())
        assertEquals(0, pixels[1].toInt())
        val last = (canvas.width - 1) * 4
        assertEquals(0, pixels[last].toInt())
        assertEquals(255, pixels[last + 1].toInt())
        host.close()
        assertFalse(root.hasChildNodes())
        assertEquals(0, canvas.width)
        assertEquals(0, canvas.height)
    }

    @Test
    fun playerHeadPreservesFaceAndHatInSeparatePaintLayers() {
        val skin =
            createDrawImage(
                IntSize(64, 64),
                IntArray(4096) { index ->
                    when (index % 64) {
                        in 8..15 -> 0xffff0000.toInt()
                        in 40..47 -> 0x8000ff00.toInt()
                        else -> 0
                    }
                },
            )
        val root = document.createElement("div") as HTMLElement
        val host = mountWeb(UiDefinition { Stack { PlayerHead(PlayerSkinSource.Pixels(skin), PlayerHeadScale(1)) } }, root, IntSize(16, 16))
        assertEquals(2, root.querySelectorAll("canvas").length)
        host.close()
        assertFalse(root.hasChildNodes())
    }
}
