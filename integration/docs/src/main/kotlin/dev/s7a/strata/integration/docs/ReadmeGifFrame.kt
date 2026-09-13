package dev.s7a.strata.integration.docs

import java.awt.image.BufferedImage

/**
 * Caller-owned opaque frame and its positive GIF hold time in hundredths of a second.
 * The image remains confined to the generation thread and must not change during encoding.
 *
 * @property image complete presentation frame.
 * @property delayCentiseconds hold time representable by GIF metadata.
 */
internal data class ReadmeGifFrame(
    val image: BufferedImage,
    val delayCentiseconds: Int,
) {
    init {
        require(delayCentiseconds in 1..65535) { "GIF frame delay must be positive and fit its metadata." }
    }
}
