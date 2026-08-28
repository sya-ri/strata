package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Classifies final native image differences only after independent float shader/geometry evidence agrees.
 * The fixture must begin with one opaque viewport fill and contain only clipped sampled text afterward.
 * Reference histories originate exclusively in immutable portable resource commands, never in captured native pixels.
 * One comparison owns this detached scene; methods retain no observations and may be called independently.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftFontGpuComparison(
    commands: List<DrawCommand>,
    viewport: IntSize,
    private val scale: Int,
    precision: MinecraftFontGpuPrecision,
) {
    private val source = MinecraftFontRasterSource(scale, precision)
    private val background: MinecraftFontRasterSample
    private val quads: List<Quad>

    init {
        require(0 < scale && 0 < viewport.width && 0 < viewport.height)
        val bounds = IntRect(0, 0, viewport.width, viewport.height)
        val fill = checkNotNull(commands.firstOrNull() as? DrawCommand.FillRectangle) { "The font proof requires an opaque viewport fill first." }
        check(fill.bounds == bounds) { "The font proof requires an exact viewport background." }
        background = MinecraftFontRasterSample.background(fill.color.value)
        quads = collect(commands.drop(1), bounds)
    }

    /**
     * Requires the candidate's deterministic byte result, then native float agreement and a propagated RGBA8 bound.
     * Geometry, tint, ordering, clipping, and non-boundary sampling errors remain unverified even if their byte difference is small.
     */
    fun classify(
        x: Int,
        y: Int,
        observation: Observation,
    ): Difference {
        if (observation.nativeArgb == observation.portableArgb) return Difference.Exact
        val canonical = histories(x, y, false).single()
        if (canonical.argb != observation.portableArgb) return Difference.UnverifiedPortableRaster
        val histories = histories(x, y, true)
        val floats = histories.filter { it.contains(observation.nativeFloat) }
        if (floats.isEmpty()) return Difference.UnverifiedNativeFloat
        val accepted = floats.firstOrNull { it.contains(observation.nativeArgb) } ?: return Difference.UnverifiedNativeColor
        val boundary = accepted.boundary && accepted.argb != canonical.argb
        return when {
            boundary && accepted.argb != observation.nativeArgb -> Difference.GpuRasterBoundaryAndColor
            boundary -> Difference.GpuRasterBoundary
            else -> Difference.GpuColorConversion
        }
    }

    private fun histories(
        x: Int,
        y: Int,
        ambiguous: Boolean,
    ): List<MinecraftFontRasterSample> {
        var histories = listOf(background)
        for (quad in quads) {
            if (quad.contains(x, y, scale).not()) continue
            val selection = source.select(quad.command, x, y, ambiguous)
            if (selection.colors.isNotEmpty()) histories = appendFragment(histories, quad.command, selection)
        }
        return histories
    }

    private fun appendFragment(
        histories: List<MinecraftFontRasterSample>,
        command: DrawCommand.SampledImage,
        selection: MinecraftFontRasterSource.Selection,
    ): List<MinecraftFontRasterSample> {
        val next = mutableListOf<MinecraftFontRasterSample>()
        histories.forEach { previous ->
            if (selection.maySkip) next += previous.copy(boundary = previous.boundary || selection.uncertain)
            selection.colors.forEach { color ->
                next += previous.blend(color, command.tint.value, command.alphaCutoff, selection.uncertain)
            }
        }
        val result = next.distinct()
        check(result.size <= 256) { "The native font pixel proof exceeded its bounded fragment alternatives." }
        return result
    }

    private fun collect(
        commands: List<DrawCommand>,
        viewport: IntRect,
    ): List<Quad> {
        val result = mutableListOf<Quad>()
        val clips = mutableListOf(viewport)
        commands.forEach { command ->
            when (command) {
                is DrawCommand.SampledImage -> {
                    result += Quad(command, clips.last())
                }

                is DrawCommand.PushClip -> {
                    val previous = clips.last()
                    val left = maxOf(previous.left, command.bounds.left)
                    val top = maxOf(previous.top, command.bounds.top)
                    clips += IntRect(left, top, maxOf(left, minOf(previous.right, command.bounds.right)), maxOf(top, minOf(previous.bottom, command.bounds.bottom)))
                }

                DrawCommand.PopClip -> {
                    check(1 < clips.size) { "Unbalanced font proof clip stack." }
                    clips.removeAt(clips.lastIndex)
                }

                else -> {
                    error("The native font proof supports only the opaque background and sampled glyph commands.")
                }
            }
        }
        check(clips.size == 1) { "Unbalanced font proof clip stack." }
        return result.toList()
    }

    private data class Quad(
        val command: DrawCommand.SampledImage,
        val clip: IntRect,
    ) {
        fun contains(
            x: Int,
            y: Int,
            scale: Int,
        ): Boolean = clip.left * scale <= x && x < clip.right * scale && clip.top * scale <= y && y < clip.bottom * scale
    }

    /**
     * One observed pixel pair plus the same native scene's independently captured float target sample.
     */
    data class Observation(
        val nativeArgb: Int,
        val portableArgb: Int,
        val nativeFloat: MinecraftFontFloatImage.Sample,
    )

    /**
     * Typed outcomes distinguish verified device effects from every unclassified or candidate-side failure.
     */
    enum class Difference(
        val accepted: Boolean,
    ) {
        /**
         * The native and portable ARGB pixels are identical.
         */
        Exact(true),

        /**
         * Matching float fragment history explains the bounded final channel conversion difference.
         */
        GpuColorConversion(true),

        /**
         * Only a measured coverage edge or nearest-texel tie explains the native pixel.
         */
        GpuRasterBoundary(true),

        /**
         * Both a measured raster boundary and bounded final channel conversion are required.
         */
        GpuRasterBoundaryAndColor(true),

        /**
         * The portable pixel disagrees with independent evaluation of its resource commands.
         */
        UnverifiedPortableRaster(false),

        /**
         * No permitted resource-derived fragment history matches the native float pixel.
         */
        UnverifiedNativeFloat(false),

        /**
         * Matching float histories cannot explain the native ARGB pixel within the channel bounds.
         */
        UnverifiedNativeColor(false),
    }
}
