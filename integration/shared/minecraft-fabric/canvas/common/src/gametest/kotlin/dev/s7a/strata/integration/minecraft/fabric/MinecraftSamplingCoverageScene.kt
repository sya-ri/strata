@file:Suppress("DEPRECATION") // Loaded compatibility controls retain the one-shot screen entry point.

package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Drives actual screen repaint and layout cutoffs without replacing private presenter inputs.
 * All updates belong to the client thread, and paint observes only the binding's committed immutable snapshot.
 * One invisible trailing spacer invalidates the flattened list without repainting the unchanged sampled Canvas.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftSamplingCoverageScene(
    internal val case: Case,
    private val viewport: IntSize,
) {
    /**
     * Fixed native control inventory corresponding to the eight frozen CPU frame-only cases.
     */
    internal enum class Case {
        CleanSameCommandIdentity,
        ChangedListSameCommands,
        OrdinaryDirectNoMap,
        InvisibleNoMap,
        ExactBudgetFallback,
        UnsupportedEffectsFallback,
        MixedCompositionBarrier,
        UnchangedMetadataEquivalentOutput,
    }

    private val owner = Thread.currentThread()
    private val trailing = mutableStateOf(1)
    private var revision = 0
    private var image = pattern()
    private var failure: Throwable? = null
    private val size = if (case == Case.ExactBudgetFallback) IntSize(4096, 4096) else IntSize(64, 64)
    private val source =
        CanvasSource {
            object : CanvasBinding {
                private var captured = snapshot()
                private var committed = captured

                override fun captureFrame() {
                    captured = snapshot()
                }

                override fun commitFrame(): Boolean {
                    val changed = captured.revision != committed.revision
                    committed = captured
                    return changed
                }

                override fun paint(scope: PaintScope) {
                    committed.failure?.let { throw it }
                    paint(scope, committed.image)
                }

                override fun close(): Unit = Unit
            }
        }

    /**
     * Creates one one-shot screen definition; the Canvas source identity remains stable across layout changes.
     */
    internal fun definition(): ScreenDefinition =
        ScreenDefinition("Exact sampling coverage loaded controls") {
            Row {
                Canvas(source, size)
                Spacer(Modifier.Empty.size(trailing.value, 1))
            }
        }

    /**
     * Changes only unpainted sibling layout, retaining the sampled Canvas picture and command identities.
     */
    internal fun changeList() {
        requireOwner()
        trailing.value = if (trailing.value == 1) 2 else 1
    }

    /**
     * Forces a new local picture with exactly equal command values and the same immutable source identity.
     */
    internal fun repaint() {
        requireOwner()
        revision = Math.incrementExact(revision)
    }

    /**
     * Replaces the current source by a distinct equal-pixel image and enqueues a repaint revision.
     */
    internal fun replaceSource() {
        requireOwner()
        image = pattern()
        repaint()
    }

    /**
     * Hashes this actual native-control source as big-endian ARGB words, outside presentation or timing.
     */
    internal fun sourcePixelsSha256(): String {
        requireOwner()
        val pixels = image.copyArgb()
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES).also { it.asIntBuffer().put(pixels) }.array()
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    /**
     * Injects a primary paint failure before native preparation or GUI submission.
     */
    internal fun fail(marker: Throwable) {
        requireOwner()
        failure = marker
        repaint()
    }

    private fun snapshot(): Snapshot {
        requireOwner()
        return Snapshot(revision, image, failure)
    }

    private fun paint(
        scope: PaintScope,
        image: DrawImage,
    ) {
        scope.fillRectangle(IntRect(0, 0, viewport.width, viewport.height), ArgbColor(0xFF112233.toInt()))
        val source = if (case == Case.OrdinaryDirectNoMap) FloatRect(0f, 0f, 64f, 48f) else FloatRect(0.25f, 0.375f, 63.75f, 47.625f)
        val destination =
            when (case) {
                Case.OrdinaryDirectNoMap -> FloatRect(0f, 0f, 64f, 48f)
                Case.InvisibleNoMap -> FloatRect(128f, 128f, 192f, 192f)
                Case.ExactBudgetFallback -> FloatRect(0f, 0f, 4096f, 4096f)
                else -> FloatRect(0f, 0f, 64f, 64f)
            }
        val tint =
            if (case == Case.OrdinaryDirectNoMap) {
                ArgbColor(-1)
            } else if (case == Case.UnsupportedEffectsFallback) {
                ArgbColor(0xFF817263.toInt())
            } else {
                ArgbColor(0xFFFF00FF.toInt())
            }
        val cutoff =
            if (case == Case.OrdinaryDirectNoMap) {
                0f
            } else if (case == Case.UnsupportedEffectsFallback) {
                0.5f
            } else {
                1f
            }
        scope.withClip(IntRect(0, 0, size.width, size.height)) {
            if (case == Case.MixedCompositionBarrier) scope.fillRectangle(IntRect(0, 0, 64, 64), ArgbColor(0x80456789.toInt()))
            scope.sampledImage(image, source, destination, tint = tint, alphaCutoff = cutoff)
            if (case == Case.MixedCompositionBarrier) scope.fillRectangle(IntRect(0, 0, 64, 64), ArgbColor(0x80765432.toInt()))
        }
    }

    private fun pattern(): DrawImage {
        val alphas = intArrayOf(0, 1, 127, 254, 255)
        return createDrawImage(IntSize(64, 48)) { x, y ->
            (alphas[(x + y * 3) % 5] shl 24) or (((x * 37 + y * 11) and 255) shl 16) or (((x * 13 + y * 43) and 255) shl 8) or ((x * 71 + y * 19) and 255)
        }
    }

    private fun requireOwner() {
        check(Thread.currentThread() === owner)
    }

    /**
     * One frame cutoff; retained history is limited to the binding's current captured and committed values.
     */
    private data class Snapshot(
        val revision: Int,
        val image: DrawImage,
        val failure: Throwable?,
    )
}
