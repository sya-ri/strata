package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.render.DrawImage

/**
 * Validates the shared source and destination contract for image commands.
 *
 * @param image the immutable source image.
 * @param source the source rectangle in image pixel coordinates.
 * @param destination the destination rectangle in local or tree coordinates.
 * @throws IllegalArgumentException when either rectangle is empty or the source is outside [image].
 */
@JvmSynthetic
internal fun validateBlitImage(
    image: DrawImage,
    source: IntRect,
    destination: IntRect,
) {
    require(0 < source.width && 0 < source.height) { "Image source must be nonempty." }
    require(
        0 <= source.left && source.right <= image.size.width &&
            0 <= source.top && source.bottom <= image.size.height,
    ) {
        "Image source must be contained in the image."
    }
    require(0 < destination.width && 0 < destination.height) { "Image destination must be nonempty." }
}
