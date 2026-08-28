package dev.s7a.strata.runtime

import dev.s7a.strata.geometry.FloatRect
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

/**
 * Validates a sampled image command without retaining or modifying its immutable inputs.
 *
 * This function has no thread affinity and is shared by local and tree-coordinate command construction.
 *
 * @param image the immutable source image.
 * @param source the fractional source rectangle in image pixel coordinates.
 * @param destination the fractional destination in local or tree coordinates.
 * @param alphaCutoff the inclusive minimum normalized alpha after tint multiplication.
 * @throws IllegalArgumentException when a rectangle is empty, the source is outside [image], or the cutoff is invalid.
 */
@JvmSynthetic
internal fun validateSampledImage(
    image: DrawImage,
    source: FloatRect,
    destination: FloatRect,
    alphaCutoff: Float,
) {
    require(0f < source.width && 0f < source.height) { "Image source must be nonempty." }
    require(
        0f <= source.left && source.right.toDouble() <= image.size.width.toDouble() &&
            0f <= source.top && source.bottom.toDouble() <= image.size.height.toDouble(),
    ) {
        "Image source must be contained in the image."
    }
    require(0f < destination.width && 0f < destination.height) { "Image destination must be nonempty." }
    require(alphaCutoff.isFinite() && 0f <= alphaCutoff && alphaCutoff <= 1f) {
        "Image alpha cutoff must be finite and between zero and one."
    }
}
