@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Builds one loaded-client parity scene that interleaves portable and native sampled-image layers.
 *
 * Each attachment owns only an input-passive binding over immutable fixture pixels, paints on the tree owner thread, and releases no external state.
 * The fixed 256 by 144 design surface is enlarged by a fractional 1.25 scale into the 320 by 180 viewport.
 * A clipped direct layer and its following portable layer overlap on a fractional edge pixel omitted by Minecraft's transformed bounds, so parity depends on preserving display-list order instead of inferred overlap.
 * A different viewport fails before the definition is returned so the native and headless paths compare the same full-frame pixels without letterboxing.
 *
 * @param viewport complete logical and physical frame size at GUI scale one.
 * @return one-shot definition covering the frame with deterministic pixels.
 */
internal fun createSampledImageParityScreenDefinition(viewport: IntSize): ScreenDefinition {
    val contentSize = IntSize(256, 144)
    require(viewport == IntSize(320, 180)) {
        "Sampled-image parity requires the 320 by 180 fractional-scale viewport."
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
    val blitted =
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
                    scope.fillRectangle(IntRect(0, 0, contentSize.width, contentSize.height), ArgbColor(0xFF000000.toInt()))
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
                        blitted,
                        IntRect(0, 0, 2, 2),
                        IntRect(4, 24, 44, 32),
                    )
                    scope.withClip(IntRect(60, 40, 100, 70)) {
                        scope.sampledImage(
                            sampled,
                            FloatRect(1f, 0f, 5f, 3f),
                            FloatRect(60f, 41.5f, 92f, 61.5f),
                            alphaCutoff = 0f,
                        )
                    }
                    scope.fillRectangle(IntRect(60, 61, 92, 62), ArgbColor(0xFF123456.toInt()))
                }

                override fun close(): Unit = Unit
            }
        }
    return ScreenDefinition("Sampled image pixel parity") {
        Stack(
            modifier =
                Modifier.Empty
                    .size(viewport.width, viewport.height)
                    .scaleToFit(contentSize, allowUpscaling = true),
        ) {
            Canvas(source, contentSize)
        }
    }
}
