package dev.s7a.strata.runtime.web

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.ui.UiDefinition
import kotlinx.browser.document
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies actual canvas pixels for prepared resources and normalized face/hat layers.
 */
internal class WebImageTest {
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
