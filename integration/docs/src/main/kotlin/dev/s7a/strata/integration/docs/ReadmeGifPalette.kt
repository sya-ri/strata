package dev.s7a.strata.integration.docs

import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel

/**
 * Builds one deterministic color table for a bounded storyboard, preventing per-frame palette flicker.
 * All histograms and nearest-color mappings are local derived data released when conversion returns.
 */
internal object ReadmeGifPalette {
    /**
     * Converts at most 32 fixed-size opaque frames to a shared palette without dithering text or pixel art.
     * The caller retains the input images and owns every returned indexed image.
     */
    fun convert(frames: List<BufferedImage>): List<BufferedImage> {
        require(frames.isNotEmpty() && frames.size <= 32) { "README GIF must contain one to 32 frames." }
        val histogram = HashMap<Int, Int>()
        frames.forEach { frame ->
            require(frame.width == 1200 && frame.height == 900) { "README GIF dimensions must be fixed." }
            pixels(frame).forEach { color -> histogram[color] = (histogram[color] ?: 0) + 1 }
        }
        val colors =
            histogram.entries
                .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
                .take(256)
                .map { it.key }
        val palette =
            IndexColorModel(
                8,
                colors.size,
                ByteArray(colors.size) { (colors[it] shr 16).toByte() },
                ByteArray(colors.size) { (colors[it] shr 8).toByte() },
                ByteArray(colors.size) { colors[it].toByte() },
            )
        val indices = histogram.keys.associateWith { color -> nearest(color, colors).toByte() }
        return frames.map { frame ->
            BufferedImage(frame.width, frame.height, BufferedImage.TYPE_BYTE_INDEXED, palette).also { indexed ->
                val converted = pixels(frame).map { indices.getValue(it) }.toByteArray()
                indexed.raster.setDataElements(0, 0, frame.width, frame.height, converted)
            }
        }
    }

    private fun pixels(image: BufferedImage): IntArray = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

    private fun nearest(
        color: Int,
        palette: List<Int>,
    ): Int =
        palette.indices.minBy { index ->
            val candidate = palette[index]
            val red = (color shr 16 and 255) - (candidate shr 16 and 255)
            val green = (color shr 8 and 255) - (candidate shr 8 and 255)
            val blue = (color and 255) - (candidate and 255)
            red * red + green * green + blue * blue
        }
}
