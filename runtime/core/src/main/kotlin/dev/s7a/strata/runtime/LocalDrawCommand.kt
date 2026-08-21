package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PlatformDrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Internal local-coordinate drawing command retained before tree translation.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal sealed interface LocalDrawCommand {
    /**
     * Fills one local rectangle.
     *
     * @property bounds local rectangle bounds.
     * @property color fill color.
     */
    data class FillRectangle(
        val bounds: IntRect,
        val color: ArgbColor,
    ) : LocalDrawCommand

    /**
     * Retains one image source and local destination before tree translation.
     *
     * @property image the immutable source image.
     * @property source the nonempty source rectangle in image coordinates.
     * @property destination the nonempty local destination rectangle.
     */
    data class BlitImage(
        val image: DrawImage,
        val source: IntRect,
        val destination: IntRect,
    ) : LocalDrawCommand {
        init {
            validateBlitImage(image, source, destination)
        }
    }

    /**
     * Retains one opaque platform payload and its local bounds before tree translation.
     *
     * @property command immutable platform-owned payload.
     * @property bounds nonempty local bounds.
     */
    data class Platform(
        val command: PlatformDrawCommand,
        val bounds: IntRect,
    ) : LocalDrawCommand {
        init {
            require(0 < bounds.width && 0 < bounds.height) { "Platform draw bounds must be nonempty." }
        }
    }
}
