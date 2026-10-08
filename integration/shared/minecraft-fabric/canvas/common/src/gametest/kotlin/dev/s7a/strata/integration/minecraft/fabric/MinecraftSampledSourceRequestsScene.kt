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
 * Frozen direct and ordered-composition commands whose immutable equal-pixel sources remain referentially distinct.
 * Replacement generations are constructed before timing and each is used once; callback revisions take the ordinary source cutoff.
 * All fixture state belongs to the client thread and retains no native storage, screen or binding after measurement.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftSampledSourceRequestsScene(
    private val case: MinecraftSampledSourceRequestsCorpus.Case,
    private val viewport: IntSize,
    operations: Int,
) : MinecraftNativePerformanceScene {
    private val extent = if (case.composed) 128 else 1
    private val images =
        List(if (case.replacement) operations + 1 else 1) { generation ->
            List(case.identities) {
                createDrawImage(IntSize(extent, extent)) { _, _ ->
                    if (case.composed) 0x804466AA.toInt() else 0xFF88AACC.toInt() xor (generation * 7919 and 0xFFFFFF)
                }
            }
        }
    private var revision = 0

    override fun update() {
        if (case.replacement) revision += 1
    }

    override fun definition(): UiDefinition =
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
                viewport,
            )
        }

    private fun paint(
        scope: PaintScope,
        generation: Int,
    ) {
        scope.fillRectangle(IntRect(0, 0, viewport.width, viewport.height), ArgbColor(0xFF102030.toInt()))
        val source = FloatRect(0f, 0f, extent.toFloat(), extent.toFloat())
        repeat(case.occurrences) { index ->
            val x = (index % 64 * 2).toFloat()
            val y = (index / 64 * 2).toFloat()
            val destination = if (case.composed) FloatRect(0f, 0f, 320f, 180f) else FloatRect(x, y, x + 2f, y + 2f)
            scope.sampledImage(images[generation][index % case.identities], source, destination, if (case.composed) ArgbColor(0xC0BFD7EF.toInt()) else ArgbColor(-1), alphaCutoff = 0f)
        }
    }
}
