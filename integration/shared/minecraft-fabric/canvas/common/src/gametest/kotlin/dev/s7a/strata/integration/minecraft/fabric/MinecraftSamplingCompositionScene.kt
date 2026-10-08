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
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Two prebuilt immutable command streams isolate proof preparation from source creation and preserve the original complete background.
 * One full background and the remaining one-pixel fills keep the dense control about command traversal rather than repeated full-screen rasterization.
 * Client-thread revisions use attachment-scoped capture/commit; paint only reads the committed stream and borrows no native state.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftSamplingCompositionScene(
    private val case: MinecraftSamplingCompositionCorpus.Case,
    private val viewport: IntSize,
) : MinecraftNativePerformanceScene {
    private val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0x807195B3.toInt(), 0, -1))
    private val frames = List(2, ::commands)
    private var revision = 0

    override fun update() {
        if (case.changed) revision = 1 - revision
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

    private fun commands(generation: Int): List<DrawCommand> {
        val background = DrawCommand.FillRectangle(IntRect(0, 0, viewport.width, viewport.height), ArgbColor(0xFF7195B3.toInt()))
        val opaque = DrawCommand.FillRectangle(IntRect(0, 0, 1, 1), ArgbColor(-1))
        val prototype = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(8f, 8f, 32f, 24f), ArgbColor(0xFF00FFFF.toInt()), alphaCutoff = 1f)
        val touching = case == MinecraftSamplingCompositionCorpus.Case.TouchingChanged
        val stepX = if (touching) 24f else 48f
        val stepY = if (touching) 16f else 40f
        val placements =
            List(case.masks) { index ->
                val x = 8f + generation * 2 + index % 8 * stepX
                val y = 8f + index / 8 * stepY
                val destination = if (case == MinecraftSamplingCompositionCorpus.Case.OverlappingChanged) FloatRect(8f + generation * 2, 8f, 32f + generation * 2, 24f) else FloatRect(x, y, x + 24f, y + 16f)
                if (case == MinecraftSamplingCompositionCorpus.Case.OrdinaryLargeChanged) {
                    prototype.copy(destination = destination, tint = ArgbColor(-1), alphaCutoff = 0f)
                } else {
                    prototype.copy(destination = destination)
                }
            }
        val base = listOf(background) + List(case.fills - 1) { opaque } + placements
        val first = DrawCommand.FillRectangle(IntRect(400, 180, 404, 184), ArgbColor(0x80472853.toInt()))
        val second = first.copy(color = ArgbColor(0x80007BBC.toInt()))
        return when (case) {
            MinecraftSamplingCompositionCorpus.Case.OrdinaryLargeChanged -> {
                listOf(background, DrawCommand.PushClip(IntRect(0, 0, viewport.width, viewport.height))) + List(case.fills - 1) { opaque } + placements + prototype.copy(tint = ArgbColor(0x00123456), alphaCutoff = 0.25f) + DrawCommand.PopClip
            }

            MinecraftSamplingCompositionCorpus.Case.EarlyBlockedChanged -> {
                listOf(first) + base + second
            }

            MinecraftSamplingCompositionCorpus.Case.LateBlockedChanged -> {
                base + listOf(first, second)
            }

            else -> {
                base
            }
        }
    }

    private fun paint(
        scope: PaintScope,
        generation: Int,
    ) {
        val commands = frames[generation]
        if (case == MinecraftSamplingCompositionCorpus.Case.OrdinaryLargeChanged) {
            emit(scope, commands.first())
            scope.withClip(IntRect(0, 0, viewport.width, viewport.height)) {
                for (index in 2 until commands.lastIndex) emit(scope, commands[index])
            }
        } else {
            commands.forEach { emit(scope, it) }
        }
    }

    private fun emit(
        scope: PaintScope,
        command: DrawCommand,
    ) {
        when (command) {
            is DrawCommand.FillRectangle -> scope.fillRectangle(command.bounds, command.color)
            is DrawCommand.SampledImage -> scope.sampledImage(command.image, command.source, command.destination, command.tint, command.alphaCutoff)
            else -> error("The compiled fixture emitted an unsupported drawing command")
        }
    }
}
