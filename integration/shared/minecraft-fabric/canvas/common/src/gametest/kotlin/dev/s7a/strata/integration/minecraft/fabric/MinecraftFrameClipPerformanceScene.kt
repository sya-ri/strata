package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Uses ordinary source cutoffs to alternate changed paint while keeping images, geometry and native source ownership fixed.
 * Clip nodes run through retained core transforms; the leaf restores integer coordinates for ordinary fill/blit work.
 * Clean cases enqueue no revision and exercise the actual screen's prepared-frame identity bypass.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftFrameClipPerformanceScene(
    private val case: MinecraftSampledPerformanceCase,
    private val viewport: IntSize,
    private val native: CanvasSource,
) {
    private val size = if (case.smallClipViewport) IntSize(32, 32) else viewport
    private val image = createDrawImage(IntSize(4, 4), IntArray(16) { 0xFF112200.toInt() or it })
    private var revision = 0

    /** Publishes one new paint observation outside the declarative frame; clean controls retain the original observation. */
    internal fun update() {
        if (case.mode != MinecraftSampledPerformanceCase.Mode.FrameClipsClean) revision += 1
    }

    /** Creates one attachment-scoped binding and bounded clip tree, borrowing the externally owned native source. */
    internal fun definition(): UiDefinition =
        UiDefinition(case.name) {
            clips(0, 0.0)
        }

    private fun UiScope.clips(
        level: Int,
        offset: Double,
    ) {
        if (level == case.clipDepth) {
            canvasTestTransform(-offset) {
                Stack {
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
                                    paintPrimitives(scope, committed)
                                }

                                override fun close(): Unit = Unit
                            }
                        },
                        size,
                    )
                    Canvas(native, IntSize(4, 4), Modifier.Empty.padding(4))
                }
            }
        } else {
            val target =
                when (checkNotNull(case.clipPattern)) {
                    MinecraftSampledPerformanceCase.ClipPattern.Integer -> 0.0
                    MinecraftSampledPerformanceCase.ClipPattern.Fractional -> 0.25
                    MinecraftSampledPerformanceCase.ClipPattern.Mixed -> if (level % 2 == 0) 0.0 else 0.25
                }
            canvasTestTransform(target - offset) {
                canvasTestClip(size) { clips(level + 1, target) }
            }
        }
    }

    private fun paintPrimitives(
        scope: PaintScope,
        revision: Int,
    ) {
        val source = IntRect(0, 0, 4, 4)
        val destination = IntRect(16, 16, 20, 20)
        val floating = FloatRect(16f, 16f, 20f, 20f)
        repeat(case.clipPrimitives) { index ->
            when (Primitive.entries[index % Primitive.entries.size]) {
                Primitive.Fill -> scope.fillRectangle(IntRect(0, 0, size.width, size.height), ArgbColor(0x80456789.toInt() xor (revision and 1)))
                Primitive.Image -> scope.blitImage(image, source, destination)
            }
            if (index == case.clipPrimitives / 2) {
                scope.sampledImage(image, FloatRect(0f, 0f, 4f, 4f), floating, alphaCutoff = 0f)
                scope.fillRectangle(destination, ArgbColor(0xFF223344.toInt()))
                scope.sampledImage(image, FloatRect(0f, 0f, 4f, 4f), floating, alphaCutoff = 0.1f)
            }
        }
    }

    private enum class Primitive {
        Fill,
        Image,
    }
}
