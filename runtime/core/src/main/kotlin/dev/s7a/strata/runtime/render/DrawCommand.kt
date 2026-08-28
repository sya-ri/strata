package dev.s7a.strata.runtime.render

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.render.SampledImageOrientation
import dev.s7a.strata.runtime.validateBlitImage
import dev.s7a.strata.runtime.validateSampledImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * A retained drawing command in accumulated tree coordinates.
 *
 * Commands are returned in retained paint order, including explicit nested child clips and post-child overlays.
 * A backend must execute the list in that order.
 * Backends must intersect drawing with every active [PushClip] until the matching [PopClip].
 * The core records these contracts without clipping, blending, or sampling pixels.
 * Portable commands carry platform-neutral values, while the opt-in [Platform] variant preserves an opaque version-adapter payload without teaching core its type.
 * Version 0.1.1 adds [SampledImage] to this sealed hierarchy; older exhaustive backends must handle or explicitly reject it before producing output.
 * Existing JVM members remain available, but an older compiled visitor can fail when given the new variant.
 */
public sealed interface DrawCommand {
    /**
     * Fills a rectangle with one color.
     *
     * @property bounds the rectangle in accumulated tree coordinates.
     * @property color the fill color.
     */
    public data class FillRectangle(
        public val bounds: IntRect,
        public val color: ArgbColor,
    ) : DrawCommand

    /**
     * Copies an image source rectangle into a tree-coordinate destination rectangle.
     *
     * The source is nonempty and contained in [image].
     * The destination is nonempty and uses half-open tree coordinates.
     * Backends execute this command in the supplied command order; core does not clip or sample it.
     *
     * @property image the immutable source image retained by reference without copying pixels.
     * @property source the source rectangle in image pixel coordinates.
     * @property destination the destination rectangle in accumulated tree coordinates.
     * @throws IllegalArgumentException when the source is empty, outside [image], or the destination is empty.
     */
    public data class BlitImage(
        public val image: DrawImage,
        public val source: IntRect,
        public val destination: IntRect,
    ) : DrawCommand {
        init {
            validateBlitImage(image, source, destination)
        }
    }

    /**
     * Samples an immutable image independently at every output pixel in a logical destination rectangle.
     *
     * Unlike logical-pixel image drawing, increasing the backend output scale preserves source detail within one logical pixel.
     * Nearest sampling uses output pixel centers against the original unclipped destination; active clips remain logical rectangles.
     * Source-over blending applies independently to every output pixel, including pixels later covered by ordinary logical commands.
     * This detached command is safe to retain or read on any thread and owns no native handle or rendering callback.
     *
     * @property image immutable source pixels retained without copying.
     * @property source nonempty contained source rectangle in image pixel coordinates.
     * @property destination nonempty half-open destination in accumulated logical tree coordinates.
     * @throws IllegalArgumentException when the source is empty, outside [image], or the destination is empty.
     */
    public data class BlitImagePixels(
        public val image: DrawImage,
        public val source: IntRect,
        public val destination: IntRect,
    ) : DrawCommand {
        init {
            validateBlitImage(image, source, destination)
        }
    }

    /**
     * Samples an immutable image through fractional geometry at the backend's final physical pixel density.
     *
     * The image is retained by reference without copying pixels and all values are safe to share between threads.
     * Backends map covered physical pixel centers through the unclipped destination and use nearest source sampling.
     * Source channels are multiplied by normalized tint channels without intermediate eight-bit rounding.
     * Samples with multiplied alpha below [alphaCutoff] are discarded before blending in command order.
     *
     * @property image the immutable source image.
     * @property source the nonempty source rectangle contained in the image, in source pixel coordinates.
     * @property destination the nonempty destination rectangle in accumulated tree coordinates.
     * @property tint the multiplicative straight ARGB color, defaulting to opaque white.
     * @property alphaCutoff the finite inclusive minimum normalized alpha from zero to one.
     * @property orientation source-axis directions applied before nearest sampling, without changing normalized bounds.
     * @throws IllegalArgumentException when a rectangle is empty, the source is outside [image], or the cutoff is invalid.
     */
    public data class SampledImage(
        public val image: DrawImage,
        public val source: FloatRect,
        public val destination: FloatRect,
        public val tint: ArgbColor = ArgbColor(-1),
        public val alphaCutoff: Float = 0.1f,
        public val orientation: SampledImageOrientation,
    ) : DrawCommand {
        /**
         * Creates ordinary sampling with increasing source coordinates on both axes.
         * Inputs, immutable ownership, and validation follow the primary constructor.
         */
        public constructor(
            image: DrawImage,
            source: FloatRect,
            destination: FloatRect,
            tint: ArgbColor = ArgbColor(-1),
            alphaCutoff: Float = 0.1f,
        ) : this(image, source, destination, tint, alphaCutoff, SampledImageOrientation.Normal)

        init {
            validateSampledImage(image, source, destination, alphaCutoff)
        }
    }

    /**
     * Preserves an opaque platform payload at one tree-coordinate rectangle.
     *
     * Core retains [command] by reference and does not interpret, copy, or clip it.
     * A matching backend executes it in list order while applying the active [PushClip] stack.
     * Other backends must reject the payload before producing partial output.
     *
     * @property command immutable platform-owned payload.
     * @property bounds nonempty half-open tree-coordinate bounds.
     * @throws IllegalArgumentException when [bounds] is empty.
     */
    @InternalStrataRuntimeApi
    public data class Platform(
        public val command: PlatformDrawCommand,
        public val bounds: IntRect,
    ) : DrawCommand {
        init {
            require(0 < bounds.width && 0 < bounds.height) { "Platform draw bounds must be nonempty." }
        }
    }

    /**
     * Begins clipping subsequent drawing to [bounds] intersected with every active outer clip.
     *
     * Clips are nested and use half-open tree coordinates.
     * Empty bounds are valid and hide drawing until the matching [PopClip].
     *
     * @property bounds the clip rectangle in accumulated tree coordinates.
     */
    public data class PushClip(
        public val bounds: IntRect,
    ) : DrawCommand

    /**
     * Ends the most recently begun child clip.
     *
     * A backend rejects an unmatched pop or an unterminated push before producing output.
     */
    public data object PopClip : DrawCommand
}
