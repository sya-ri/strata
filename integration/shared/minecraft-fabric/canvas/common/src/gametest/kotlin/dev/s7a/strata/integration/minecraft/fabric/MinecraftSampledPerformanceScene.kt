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
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Render-thread fixture whose external updates enqueue revisions and whose binding takes the ordinary frame cutoff.
 * Source construction is untimed; replacement uses distinct prebuilt identities rather than a warm identity pool.
 * No application model, native handle, clock or cross-frame mutable raster is part of this fixture.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftSampledPerformanceScene(
    private val case: MinecraftSampledPerformanceCase,
    private val size: IntSize,
    operations: Int,
) {
    private val images =
        List(if (case.mode == MinecraftSampledPerformanceCase.Mode.Replacement) operations + 1 else 1) { generation ->
            createDrawImage(
                IntSize(case.resolution, case.resolution),
                IntArray(case.resolution * case.resolution) { index ->
                    val alpha =
                        when ((index + generation) % 3) {
                            0 -> 0
                            1 -> 128
                            else -> 255
                        }
                    (alpha shl 24) or ((index * 73471 + generation * 1337) and 0xFFFFFF)
                },
            )
        }
    private var revision = 0

    /**
     * Enqueues the next immutable revision outside declarative evaluation on the physical owner.
     */
    internal fun update() {
        if (case.mode != MinecraftSampledPerformanceCase.Mode.Stationary) revision += 1
    }

    /**
     * Creates one attachment-scoped binding; close releases no externally owned fixture input.
     */
    internal fun definition(): UiDefinition =
        UiDefinition(case.name) {
            Canvas(
                CanvasSource {
                    object : CanvasBinding {
                        private var captured = revision
                        private var committed = revision

                        override fun captureFrame() {
                            captured = revision
                        }

                        override fun commitFrame(): Boolean {
                            val changed = captured != committed
                            committed = captured
                            return changed
                        }

                        override fun paint(scope: PaintScope) {
                            paint(scope, committed)
                        }

                        override fun close(): Unit = Unit
                    }
                },
                size,
            )
        }

    private fun paint(
        scope: PaintScope,
        revision: Int,
    ) {
        val image = images[if (case.mode == MinecraftSampledPerformanceCase.Mode.Replacement) revision else 0]
        val source = FloatRect(0.125f, 0.25f, image.size.width - 0.25f, image.size.height - 0.125f)
        scope.fillRectangle(IntRect(0, 0, size.width, size.height), ArgbColor(0xFF234567.toInt()))
        val shift = if (case.mode == MinecraftSampledPerformanceCase.Mode.Translation) revision % 8 * 0.25f else 0f
        val inset = if (case.mode == MinecraftSampledPerformanceCase.Mode.Resize) revision % 8 * 0.75f else 0f
        val destination = FloatRect(0.25f + shift, 0.125f, size.width - 2.25f - inset + shift, size.height - 0.375f - inset)
        when (case.mode) {
            MinecraftSampledPerformanceCase.Mode.Clip -> {
                scope.withClip(IntRect(revision % 8, revision % 4, size.width - revision % 8, size.height - revision % 4)) {
                    scope.sampledImage(image, source, destination, alphaCutoff = 0f)
                }
            }

            MinecraftSampledPerformanceCase.Mode.OrderedRows -> {
                repeat(if (revision % 2 == 0) 32 else 64) { index ->
                    val x = index % 8 * size.width / 8
                    val y = index / 8 * size.height / 8
                    scope.fillRectangle(IntRect(x, y, minOf(size.width, x + size.width / 8), minOf(size.height, y + size.height / 8)), ArgbColor(0x807195B3.toInt()))
                    scope.sampledImage(image, source, FloatRect(x + 0.125f, y + 0.25f, minOf(size.width.toFloat(), x + size.width / 7f), minOf(size.height.toFloat(), y + size.height / 7f)), ArgbColor(0xC0BFD7EF.toInt()), alphaCutoff = 0.1f)
                }
            }

            else -> {
                scope.sampledImage(image, source, destination, alphaCutoff = 0f)
            }
        }
    }
}
