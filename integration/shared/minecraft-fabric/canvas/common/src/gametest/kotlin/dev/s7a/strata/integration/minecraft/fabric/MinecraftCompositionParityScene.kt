@file:Suppress("DEPRECATION") // Loaded parity retains the same compatibility screen factory as the existing client suite.
@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Builds complete transparent, translucent and opaque ordered tiles with images before and after real portable glyphs.
 * All source pixels and bindings belong only to this screen attachment; the fixture retains no native state or history.
 * Fractional crops, reversed axes and nested clips preserve original coordinates across GUI density and source replacement.
 */
internal fun createMinecraftCompositionParityScene(
    viewport: IntSize,
    revision: Int = 0,
): ScreenDefinition {
    val image = createDrawImage(IntSize(16, 16)) { x, y -> ((x * 16 + y) shl 24) or (((x * 73471 + y * 1337) xor revision) and 0xFFFFFF) }

    fun source(overlay: Boolean): CanvasSource =
        CanvasSource {
            object : CanvasBinding {
                override fun paint(scope: PaintScope) {
                    if (overlay.not()) {
                        scope.fillRectangle(IntRect(0, 0, viewport.width / 3, viewport.height / 2), ArgbColor(0x804A719B.toInt()))
                        scope.fillRectangle(IntRect(viewport.width / 2, 0, viewport.width, viewport.height / 2), ArgbColor(0xFF213759.toInt()))
                    }
                    scope.withClip(IntRect(4, 4, viewport.width - 4, viewport.height - 4)) {
                        scope.withClip(IntRect(8, 8, viewport.width - 8, viewport.height - 8)) {
                            repeat(12) { index ->
                                val x = 8 + index % 4 * 58 + if (overlay) 9 else 0
                                val y = 8 + index / 4 * 57 + if (overlay) 11 else 0
                                val tint =
                                    when (index % 4) {
                                        0 -> 0x017FC1E3
                                        1 -> 0x80BFD7EF.toInt()
                                        2 -> 0xFE37659B.toInt()
                                        else -> -1
                                    }
                                scope.sampledImage(image, FloatRect(0.125f, 0.375f, 15.875f, 15.625f), FloatRect(x + 0.25f, y + 0.125f, x + 83.75f, y + 72.875f), SampledImageOrientation.entries[index % 4], ArgbColor(tint), if (index % 3 == 0) 0f else 0.1f)
                            }
                            scope.blitImage(image, IntRect(1, 2, 15, 14), IntRect(12, 144, 211, 179))
                        }
                    }
                }

                override fun close(): Unit = Unit
            }
        }
    val background = source(false)
    val overlay = source(true)
    return ScreenDefinition("Ordered transparent composition parity") {
        Stack(modifier = Modifier.Empty.size(viewport.width, viewport.height)) {
            Canvas(background, viewport)
            Text("Overlapping glyphs 0123456789", modifier = Modifier.Empty.padding(16))
            Canvas(overlay, viewport)
        }
    }
}
