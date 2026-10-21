package dev.s7a.strata.runtime.web

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import kotlinx.browser.document
import kotlinx.browser.window
import org.khronos.webgl.get
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies cropped physical storage against pixel-center coverage and the original sampling transform.
 */
internal class WebImageRasterizerTest {
    @Test
    fun legacyPhysicalBlitsUseTheSameVisibleRegionAndSourceMapping() {
        val image = createDrawImage(IntSize(2, 2), intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffff00.toInt()))
        val command = DrawCommand.BlitImagePixels(image, IntRect(0, 0, 2, 2), IntRect(-10000, -10000, 10000, 10000))
        for (density in 1..3) {
            withDensity(density) {
                val canvas = document.createElement("canvas") as HTMLCanvasElement
                WebImageRasterizer.draw(canvas, command, IntSize(6, 6), FloatRect(1f, 1f, 3f, 3f))
                assertEquals(2 * density, canvas.width)
                assertEquals(2 * density, canvas.height)
                val context = canvas.getContext("2d") as CanvasRenderingContext2D
                val pixels = context.getImageData(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble()).data
                for (index in 0 until canvas.width * canvas.height) {
                    assertEquals(listOf(255, 255, 0, 255), (0..3).map { pixels[index * 4 + it].toInt() })
                }
                canvas.width = 0
                canvas.height = 0
            }
        }
    }

    @Test
    fun fractionalClipsPreserveSamplingOrientationAndTintAtEachDensity() {
        val image = createDrawImage(IntSize(4, 4), IntArray(16) { 0xff00000d.toInt() or ((it % 4 * 50) shl 16) or ((it / 4 * 60) shl 8) })
        val source = FloatRect(0.25f, 0.5f, 3.75f, 3.5f)
        val destination = FloatRect(-0.25f, 0.25f, 3.75f, 4.25f)
        val clip = FloatRect(0.6f, 0.7f, 2.7f, 3.3f)
        for (density in 1..3) {
            withDensity(density) {
                val xs = (0 until 3 * density).filter { clip.left <= (it + 0.5) / density && (it + 0.5) / density < clip.right }
                val ys = (0 until 4 * density).filter { clip.top <= (it + 0.5) / density && (it + 0.5) / density < clip.bottom }
                for (orientation in SampledImageOrientation.entries) {
                    for (physical in listOf(false, true)) {
                        val canvas = document.createElement("canvas") as HTMLCanvasElement
                        WebImageRasterizer.draw(canvas, image, source, destination, IntSize(3, 4), clip, ArgbColor(0xff8040ff.toInt()), 0f, orientation, physical)
                        assertEquals(xs.size, canvas.width)
                        assertEquals(ys.size, canvas.height)
                        assertEquals(
                            xs.first().toDouble() / density,
                            canvas.style.left
                                .removeSuffix("px")
                                .toDouble(),
                            0.00001,
                        )
                        assertEquals(
                            ys.first().toDouble() / density,
                            canvas.style.top
                                .removeSuffix("px")
                                .toDouble(),
                            0.00001,
                        )
                        val context = canvas.getContext("2d") as CanvasRenderingContext2D
                        val pixels = context.getImageData(0.0, 0.0, xs.size.toDouble(), ys.size.toDouble()).data
                        ys.forEachIndexed { row, y ->
                            xs.forEachIndexed { column, x ->
                                val px = (x + 0.5) / density
                                val py = (y + 0.5) / density
                                val sampleX = if (physical) px else floor(px) + 0.5
                                val sampleY = if (physical) py else floor(py) + 0.5
                                val fx = (sampleX - destination.left) / destination.width
                                val fy = (sampleY - destination.top) / destination.height
                                val sx = floor(if (orientation.flipX) source.right - fx * source.width else source.left + fx * source.width).toInt().coerceIn(0, 3)
                                val sy = floor(if (orientation.flipY) source.bottom - fy * source.height else source.top + fy * source.height).toInt().coerceIn(0, 3)
                                val expected = listOf((sx * 50 * 128 / 255.0).roundToInt(), (sy * 60 * 64 / 255.0).roundToInt(), 13, 255)
                                val index = (row * xs.size + column) * 4
                                assertEquals(expected, (0..3).map { pixels[index + it].toInt() }, "$density $orientation $physical at $x,$y")
                            }
                        }
                        canvas.width = 0
                        canvas.height = 0
                    }
                }
            }
        }
    }

    @Test
    fun invisibleImagesReleaseStorageWithoutRequestingAContext() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val source = FloatRect(0f, 0f, 1f, 1f)
        val visible = FloatRect(-10000f, -10000f, 10000f, 10000f)
        val cases =
            listOf(
                FloatRect(10000f, 10000f, 20000f, 20000f) to null,
                visible to FloatRect(0f, 0f, 0f, 8f),
                FloatRect(1f, 1f, 2f, 2f) to FloatRect(3f, 3f, 4f, 4f),
                FloatRect(0.01f, 0.01f, 0.02f, 0.02f) to null,
            )
        for (density in 1..3) {
            withDensity(density) {
                cases.forEach { (destination, clip) ->
                    val canvas = document.createElement("canvas") as HTMLCanvasElement
                    canvas.width = 4
                    canvas.height = 4
                    canvas.asDynamic().getContext = { _: dynamic -> error("Invisible image requested pixel storage") }
                    WebImageRasterizer.draw(canvas, image, source, destination, IntSize(8, 8), clip, ArgbColor(-1), 0f, SampledImageOrientation.Normal)
                    assertEquals(0, canvas.width)
                    assertEquals(0, canvas.height)
                    assertEquals("0px", canvas.style.width)
                    assertEquals("0px", canvas.style.height)
                    assertEquals("0px", canvas.style.left)
                    assertEquals("0px", canvas.style.top)
                }
            }
        }
    }

    @Test
    fun visiblePixelBudgetStillRejectsOversizedImagesBeforeAllocation() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(-1))
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = 1
        canvas.height = 1
        canvas.asDynamic().getContext = { _: dynamic -> error("Oversized image requested pixel storage") }
        withDensity(3) {
            val failure =
                assertFailsWith<IllegalArgumentException> {
                    WebImageRasterizer.draw(canvas, image, FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 1400f, 1400f), IntSize(1400, 1400), null, ArgbColor(-1), 0f, SampledImageOrientation.Normal)
                }
            assertTrue(failure.message.orEmpty().contains("physical pixel budget"))
            assertEquals(1, canvas.width)
            assertEquals(1, canvas.height)
        }
    }

    @Test
    fun alphaCutoffStillDiscardsSamplesAfterCropping() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(0x40ffffff))
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        withDensity(2) {
            WebImageRasterizer.draw(canvas, image, FloatRect(0f, 0f, 1f, 1f), FloatRect(-10000f, -10000f, 10000f, 10000f), IntSize(1, 1), null, ArgbColor(-1), 0.5f, SampledImageOrientation.Normal)
            val context = canvas.getContext("2d") as CanvasRenderingContext2D
            val pixels = context.getImageData(0.0, 0.0, 2.0, 2.0).data
            assertEquals(List(16) { 0 }, (0 until 16).map { pixels[it].toInt() })
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
}
