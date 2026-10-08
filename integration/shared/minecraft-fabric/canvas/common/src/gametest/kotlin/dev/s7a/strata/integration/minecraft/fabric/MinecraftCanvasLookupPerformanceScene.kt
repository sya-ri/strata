package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasId
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Emits repeated placements from each attachment's real binding, keeping target count within the actual 64-set device quota.
 * The wrapper borrows the native source and delegates its cutoff/close unchanged; no token, target or lease is reconstructed.
 * Actual GUI drawing, capture, pins and fences remain in the existing shared native presentation meter.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftCanvasLookupPerformanceScene(
    private val case: MinecraftSampledPerformanceCase,
    private val native: CanvasSource,
) {
    /**
     * Creates exactly the declared target/occurrence matrix with a separate Canvas-free portable control.
     */
    internal fun definition(): UiDefinition =
        UiDefinition(case.name) {
            Stack {
                Spacer(Modifier.Empty.size(32, 32).background(ArgbColor(0xFF102030.toInt())))
                repeat(case.canvasTargets) {
                    Canvas(source(), IntSize(2, 2))
                }
            }
        }

    private fun source(): CanvasSource =
        CanvasSource { identity -> open(identity) }

    private fun open(identity: CanvasId): CanvasBinding {
        val delegate = native.open(identity)
        return object : CanvasBinding {
            override fun captureFrame() {
                delegate.captureFrame()
            }

            override fun commitFrame(): Boolean = delegate.commitFrame()

            override fun paint(scope: PaintScope) {
                repeat(case.canvasOccurrences / case.canvasTargets) { delegate.paint(scope) }
            }

            override fun close() {
                delegate.close()
            }
        }
    }
}
