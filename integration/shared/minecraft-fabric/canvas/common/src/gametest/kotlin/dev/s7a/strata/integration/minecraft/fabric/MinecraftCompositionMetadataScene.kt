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
 * Client-owned immutable source generations and ordinary binding revisions for the complete native metadata matrix.
 * Rebuilt controls repaint equal commands; replacement uses each prebuilt equal-pixel identity once, outside source creation timing.
 * Original fractional sample operands and reversed axes are emitted through the public PaintScope without metadata substitution.
 * The collector drops this fixture after closing its screen; no native handle or previous runtime input is stored here.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftCompositionMetadataScene(
    private val case: MinecraftCompositionMetadataCorpus.Case,
    private val viewport: IntSize,
    operations: Int,
) : MinecraftNativePerformanceScene {
    private val images =
        List(if (case == MinecraftCompositionMetadataCorpus.Case.ReplacementLarge) operations + 1 else 1) {
            createDrawImage(IntSize(case.sourceExtent, case.sourceExtent)) { x, y -> ((x * 19 + y * 17 and 255) shl 24) or ((x * 73471 + y * 1337) and 0xFFFFFF) }
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
        val size = if (case.small) IntSize(minOf(64, viewport.width), minOf(64, viewport.height)) else viewport
        val columns = (size.width + 255) / 256
        val rows = (size.height + 255) / 256
        val image = images[if (case == MinecraftCompositionMetadataCorpus.Case.ReplacementLarge) generation else 0]
        val phase = generation % 2
        if (case == MinecraftCompositionMetadataCorpus.Case.InsertLarge && phase == 1) addition(scope, image)
        for (group in 0 until columns * rows) {
            val left = group % columns * 256
            val top = group / columns * 256
            val area = IntRect(left, top, minOf(size.width, left + 256), minOf(size.height, top + 256))
            if (case == MinecraftCompositionMetadataCorpus.Case.NestedClipsLarge) {
                scope.withClip(area) {
                    scope.withClip(IntRect(area.left + 1, area.top + 1, maxOf(area.left + 1, area.right - 1), maxOf(area.top + 1, area.bottom - 1))) {
                        paintGroup(scope, image, area, group, phase)
                    }
                }
            } else {
                paintGroup(scope, image, area, group, phase)
            }
        }
        if (case == MinecraftCompositionMetadataCorpus.Case.RemoveLarge && phase == 0) addition(scope, image)
    }

    private fun paintGroup(
        scope: PaintScope,
        image: DrawImage,
        area: IntRect,
        group: Int,
        phase: Int,
    ) {
        scope.fillRectangle(area, ArgbColor(0x40213759))
        val scroll = if (case == MinecraftCompositionMetadataCorpus.Case.ScrollLarge) phase * 0.25f else 0f
        val sourceShift = if (case == MinecraftCompositionMetadataCorpus.Case.OneDirtyLarge && group == 0) phase * 0.25f else 0f
        val source = FloatRect(sourceShift, 0f, image.size.width.toFloat(), image.size.height.toFloat())
        val destination = FloatRect(area.left + scroll, area.top.toFloat(), area.right + scroll, area.bottom.toFloat())
        repeat(4) { pass ->
            scope.sampledImage(
                image,
                source,
                destination,
                if (pass % 2 == 0) SampledImageOrientation.Normal else SampledImageOrientation.FlipHorizontal,
                ArgbColor(tint(group, pass, phase)),
                alphaCutoff = if (case == MinecraftCompositionMetadataCorpus.Case.CutoffChangedLarge) 0.1f + phase * 0.1f else 0.1f,
            )
        }
    }

    private fun addition(
        scope: PaintScope,
        image: DrawImage,
    ) {
        scope.sampledImage(image, FloatRect(0f, 0f, image.size.width.toFloat(), image.size.height.toFloat()), FloatRect(16.125f, 12.375f, 80.25f, 76.5f), ArgbColor(0x80AACCFF.toInt()), alphaCutoff = 0.2f)
    }

    private fun tint(
        group: Int,
        pass: Int,
        phase: Int,
    ): Int =
        when (case) {
            MinecraftCompositionMetadataCorpus.Case.TintChangedLarge -> 0x80BFD7EF.toInt() xor phase
            MinecraftCompositionMetadataCorpus.Case.RepeatedTintsLarge -> if (pass % 2 == 0) 0x80BFD7EF.toInt() else 0xC037659B.toInt()
            MinecraftCompositionMetadataCorpus.Case.ReversedTintsLarge -> if ((pass + group) % 2 == 0) 0x80BFD7EF.toInt() else 0xC037659B.toInt()
            MinecraftCompositionMetadataCorpus.Case.UniqueTintsLarge -> 0x80BFD7EF.toInt() xor (group * 7919 + pass * 1337)
            else -> 0x80BFD7EF.toInt()
        }
}
