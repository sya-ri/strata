package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition

/**
 * Client-owned immutable source generations and ordinary binding revisions for the complete native target/pass matrix.
 * Rebuilt controls repaint equal commands; replacement uses each prebuilt equal-pixel identity once, outside source creation timing.
 * Original fractional sample operands and reversed axes are emitted through the public PaintScope without metadata substitution.
 * The collector drops this fixture after closing its screen; no native handle or previous runtime input is stored here.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftCompositionTargetsScene(
    private val case: MinecraftCompositionTargetsCorpus.Case,
    private val viewport: IntSize,
    operations: Int,
) : MinecraftNativePerformanceScene {
    private val images =
        List(if (case == MinecraftCompositionTargetsCorpus.Case.ReplacementLarge || case == MinecraftCompositionTargetsCorpus.Case.SourceChurnLarge) operations + 1 else 1) { generation ->
            createDrawImage(IntSize(case.sourceExtent, case.sourceExtent)) { x, y -> ((x * 19 + y * 17 and 255) shl 24) or (((x * 73471 + y * 1337) xor (if (case == MinecraftCompositionTargetsCorpus.Case.SourceChurnLarge) generation else 0)) and 0xFFFFFF) }
        }
    private var revision = 0

    override fun update() {
        if (case.stationary.not()) revision += 1
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
        val size =
            when {
                case.small -> IntSize(minOf(64, viewport.width), minOf(64, viewport.height))
                case == MinecraftCompositionTargetsCorpus.Case.MixedSizesLarge -> IntSize(maxOf(1, viewport.width - 17), maxOf(1, viewport.height - 13))
                else -> viewport
            }
        val columns = (size.width + 255) / 256
        val rows = (size.height + 255) / 256
        val replacement = case == MinecraftCompositionTargetsCorpus.Case.ReplacementLarge || case == MinecraftCompositionTargetsCorpus.Case.SourceChurnLarge
        val image = images[if (replacement) generation else 0]
        val phase = generation % 2
        for (group in 0 until columns * rows) {
            val left = group % columns * 256
            val top = group / columns * 256
            val area = IntRect(left, top, minOf(size.width, left + 256), minOf(size.height, top + 256))
            paintGroup(scope, image, area, group, phase)
        }
    }

    @Suppress("LongMethod") // One original public paint sequence covers both pass parity and complete sparse/no-op controls.
    private fun paintGroup(
        scope: PaintScope,
        image: DrawImage,
        area: IntRect,
        group: Int,
        phase: Int,
    ) {
        if (case == MinecraftCompositionTargetsCorpus.Case.ActivePassSwapLarge) {
            scope.fillRectangle(IntRect(area.left, area.top, minOf(area.right, area.left + 127), area.bottom), ArgbColor(if (phase == 0) 0x00ABCDEF else 0x80445566.toInt()))
            scope.fillRectangle(IntRect(minOf(area.right, area.left + 128), area.top, area.right, area.bottom), ArgbColor(if (phase == 0) 0x80112233.toInt() else 0x00ABCDEF))
        } else {
            scope.fillRectangle(area, ArgbColor(0x40213759 xor (group * 7919)))
        }
        val count = when (case) {
            MinecraftCompositionTargetsCorpus.Case.DenseOddLarge -> 3
            MinecraftCompositionTargetsCorpus.Case.NoOpHeavyLarge, MinecraftCompositionTargetsCorpus.Case.CutoffHeavyLarge -> 8
            MinecraftCompositionTargetsCorpus.Case.MixedPassesLarge -> group % 17 + 1
            else -> 4
        }
        val scroll = if (case == MinecraftCompositionTargetsCorpus.Case.ScrollLarge) phase * 0.25f else 0f
        val source = FloatRect(0.125f, 0.375f, image.size.width - 0.125f, image.size.height - 0.25f)
        repeat(count) { pass ->
            val sparse = case == MinecraftCompositionTargetsCorpus.Case.SparseLarge && pass % 2 == 0
            val destination = FloatRect(
                area.left + scroll + if (sparse) area.width * 0.25f + 0.125f else 0.25f,
                area.top + if (sparse) area.height * 0.25f + 0.125f else 0.125f,
                area.right + scroll - if (sparse) area.width * 0.25f + 0.125f else 0.125f,
                area.bottom - if (sparse) area.height * 0.25f + 0.125f else 0.375f,
            )
            val tint =
                if (case == MinecraftCompositionTargetsCorpus.Case.NoOpHeavyLarge && pass < 6) 0x00ABCDEF else {
                    val stable =
                        when (case) {
                            MinecraftCompositionTargetsCorpus.Case.ReplacementLarge, MinecraftCompositionTargetsCorpus.Case.SourceChurnLarge, MinecraftCompositionTargetsCorpus.Case.ScrollLarge, MinecraftCompositionTargetsCorpus.Case.ActivePassSwapLarge -> true
                            else -> false
                        }
                    val changed = if (stable || (case == MinecraftCompositionTargetsCorpus.Case.OneDirtyLarge && group != 0)) 0 else phase
                    0x80BFD7EF.toInt() xor changed
                }
            scope.sampledImage(
                image,
                source,
                destination,
                if (pass % 2 == 0) SampledImageOrientation.Normal else SampledImageOrientation.FlipHorizontal,
                ArgbColor(tint),
                alphaCutoff = if (case == MinecraftCompositionTargetsCorpus.Case.CutoffHeavyLarge && pass < 6) 1f else 0.1f,
            )
        }
        if (case == MinecraftCompositionTargetsCorpus.Case.NoOpHeavyLarge) repeat(8) { scope.fillRectangle(area, ArgbColor(0x00ABCDEF)) }
    }
}
