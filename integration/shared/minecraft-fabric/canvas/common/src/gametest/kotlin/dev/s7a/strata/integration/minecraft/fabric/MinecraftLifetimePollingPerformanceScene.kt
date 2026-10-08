package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Exercises real native lifetime polling through bounded attachments, portable barriers and immutable sampled sources.
 * Screen closure and GUI consumption stay in the shared native meter; this scene adds no collector or native ownership.
 * Portable counts identify layers within one presenter generation, while CPU fixtures separately cover 64 presenter records.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftLifetimePollingPerformanceScene(
    private val case: MinecraftSampledPerformanceCase,
    private val native: CanvasSource,
) {
    private val kind = checkNotNull(case.lifetimeKind)
    private val images =
        List(if (kind == MinecraftSampledPerformanceCase.LifetimeKind.Sources) case.lifetimeCount else 1) { index ->
            createDrawImage(IntSize(1, 1), intArrayOf(0xFF336600.toInt() or index))
        }
    private var revision = 0

    /**
     * Publishes a changed paint observation outside frame evaluation; clean controls keep the original observation.
     */
    internal fun update() {
        if (case.lifetimeChanged) revision += 1
    }

    /**
     * Creates bounded real attachments and source identities without retaining any native texture or target.
     */
    internal fun definition(): UiDefinition =
        UiDefinition(case.name) {
            Stack {
                Canvas(source(portable = false), IntSize(64, 64))
                when (kind) {
                    MinecraftSampledPerformanceCase.LifetimeKind.Targets -> {
                        repeat(case.lifetimeCount) { Canvas(native, IntSize(2, 2)) }
                    }

                    MinecraftSampledPerformanceCase.LifetimeKind.Portable -> {
                        repeat(case.lifetimeCount) {
                            Canvas(source(portable = true), IntSize(2, 2))
                            Canvas(native, IntSize(2, 2))
                        }
                    }

                    MinecraftSampledPerformanceCase.LifetimeKind.Sources -> {}
                }
            }
        }

    private fun source(portable: Boolean): CanvasSource =
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
                    scope.fillRectangle(IntRect(0, 0, scope.size.width, scope.size.height), ArgbColor(0xFF102030.toInt() xor (committed and 1)))
                    if (portable) {
                        scope.sampledImage(images.single(), FloatRect(0f, 0f, 1f, 1f), FloatRect(0f, 0f, 2f, 2f), ArgbColor(0xC0FFFFFF.toInt()), alphaCutoff = 0.1f)
                    } else if (kind == MinecraftSampledPerformanceCase.LifetimeKind.Sources) {
                        images.forEachIndexed { index, image ->
                            val x = index % 32 * 2f
                            val y = index / 32 * 2f
                            scope.sampledImage(image, FloatRect(0f, 0f, 1f, 1f), FloatRect(x, y, x + 1f, y + 1f), alphaCutoff = 0f)
                        }
                    }
                }

                override fun close() = Unit
            }
        }
}
