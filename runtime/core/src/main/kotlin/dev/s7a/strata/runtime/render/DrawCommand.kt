package dev.s7a.strata.runtime.render

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.runtime.validateBlitImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * A retained drawing command in accumulated tree coordinates.
 *
 * Commands are returned in retained paint order, including explicit nested child clips and post-child overlays.
 * A backend must execute the list in that order.
 * Backends must intersect drawing with every active [PushClip] until the matching [PopClip].
 * The core does not define blending or sampling policy.
 * Portable commands carry platform-neutral values, while the opt-in [Platform] variant preserves an opaque version-adapter payload without teaching core its type.
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
