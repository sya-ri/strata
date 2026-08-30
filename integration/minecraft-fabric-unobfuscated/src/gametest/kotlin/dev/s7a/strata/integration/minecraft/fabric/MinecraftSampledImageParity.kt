@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Builds one loaded-client parity scene that alternates portable and native sampled-image layers.
 *
 * Each attachment owns only an input-passive binding over immutable fixture pixels, paints on the tree owner thread, and releases no external state.
 * The viewport must contain the fixed test aperture; invalid dimensions fail before the definition is returned.
 *
 * @param viewport complete logical and physical frame size at GUI scale one.
 * @return one-shot definition covering the frame with deterministic pixels.
 */
internal fun createSampledImageParityScreenDefinition(viewport: IntSize): ScreenDefinition {
    require(48 <= viewport.width && 32 <= viewport.height) {
        "Sampled-image parity requires at least a 48 by 32 viewport."
    }
    val sampled =
        createDrawImage(
            IntSize(6, 4),
            intArrayOf(
                0xFFFF0000.toInt(),
                0x8000FF00.toInt(),
                0xFF0000FF.toInt(),
                0x40FFFF00,
                0xC000FFFF.toInt(),
                0xFFFF00FF.toInt(),
                0x800000FF.toInt(),
                0xFFFF0000.toInt(),
                0xC000FF00.toInt(),
                0x80FFFFFF.toInt(),
                0xFF00FFFF.toInt(),
                0x40FF00FF,
                0xFFFFFF00.toInt(),
                0x8000FFFF.toInt(),
                0xFFFF00FF.toInt(),
                0xC0FFFFFF.toInt(),
                0xFF00FF00.toInt(),
                0x80000000.toInt(),
                0xC0FF0000.toInt(),
                0xFF00FF00.toInt(),
                0x800000FF.toInt(),
                0x40FFFFFF,
                0xFFFFFF00.toInt(),
                0xFF00FFFF.toInt(),
            ),
        )
    val portable =
        createDrawImage(
            IntSize(2, 2),
            intArrayOf(
                0x80FFFFFF.toInt(),
                0x80000000.toInt(),
                0x8000FFFF.toInt(),
                0x80FF00FF.toInt(),
            ),
        )
    val source =
        CanvasSource {
            object : CanvasBinding {
                override fun paint(scope: PaintScope) {
                    scope.fillRectangle(IntRect(0, 0, viewport.width, viewport.height), ArgbColor(0xFF000000.toInt()))
                    scope.fillRectangle(IntRect(2, 2, 46, 30), ArgbColor(0xFFFF0000.toInt()))
                    scope.withClip(IntRect(5, 4, 43, 28)) {
                        scope.sampledImage(
                            sampled,
                            FloatRect(1f, 0f, 5f, 3f),
                            FloatRect(2.25f, 1.5f, 37f, 25.25f),
                            alphaCutoff = 0f,
                        )
                        scope.fillRectangle(IntRect(12, 8, 34, 22), ArgbColor(0x800000FF.toInt()))
                        scope.sampledImage(
                            sampled,
                            FloatRect(0f, 1f, 4f, 4f),
                            FloatRect(14.5f, 6.25f, 46.25f, 30.5f),
                            alphaCutoff = 0f,
                        )
                    }
                    scope.blitImage(
                        portable,
                        IntRect(0, 0, 2, 2),
                        IntRect(4, 24, 44, 32),
                    )
                }

                override fun close(): Unit = Unit
            }
        }
    return ScreenDefinition("Sampled image pixel parity") {
        Canvas(source, viewport)
    }
}
