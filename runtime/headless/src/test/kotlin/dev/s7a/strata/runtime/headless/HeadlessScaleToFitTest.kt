@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.headless

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies fixed design-surface rendering across logical GUI sizes and physical pixel densities.
 */
internal class HeadlessScaleToFitTest {
    @Test
    fun upscalingProducesEquivalentPhysicalPixelsAcrossLogicalGuiSizes() {
        val red = ArgbColor(0xFFFF0000.toInt())
        val green = ArgbColor(0xFF00FF00.toInt())
        val blue = ArgbColor(0xFF0000FF.toInt())
        val description =
            evaluateComponentTree {
                Row(
                    modifier =
                        Modifier.Empty
                            .fillMaxSize()
                            .scaleToFit(IntSize(4, 2), allowUpscaling = true),
                ) {
                    Spacer(modifier = Modifier.Empty.size(1, 2).background(red))
                    Column {
                        Spacer(modifier = Modifier.Empty.size(3, 1).background(green))
                        Spacer(modifier = Modifier.Empty.size(3, 1).background(blue))
                    }
                }
            }
        val guiRenderings =
            listOf(
                GuiRendering(viewport = IntSize(24, 12), pixelScale = 1),
                GuiRendering(viewport = IntSize(12, 6), pixelScale = 2),
                GuiRendering(viewport = IntSize(8, 4), pixelScale = 3),
                GuiRendering(viewport = IntSize(6, 3), pixelScale = 4),
            )
        val images =
            guiRenderings.map { rendering ->
                renderHeadless(description, rendering.viewport, rendering.pixelScale).image
            }

        val expectedSize = IntSize(24, 12)
        val reference = images.first()
        val referencePixels = reference.copyArgb()
        images.forEach { image ->
            assertEquals(expectedSize, image.size)
            assertArrayEquals(referencePixels, image.copyArgb())
        }
        assertEquals(red.value, reference.argbAt(0, 0))
        assertEquals(red.value, reference.argbAt(5, 11))
        assertEquals(green.value, reference.argbAt(6, 0))
        assertEquals(green.value, reference.argbAt(23, 5))
        assertEquals(blue.value, reference.argbAt(6, 6))
        assertEquals(blue.value, reference.argbAt(23, 11))
    }

    private data class GuiRendering(
        val viewport: IntSize,
        val pixelScale: Int,
    )
}
