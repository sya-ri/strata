package dev.s7a.strata.integration.docs

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Places exact source beside an unmodified complete Minecraft-style headless screen.
 * Each call creates a new caller-owned image and closes its graphics; no presentation headings or captions are painted.
 */
internal object ReadmeDemoCompositor {
    /**
     * Composes a 1200 by 900 frame; the supplied 512 by 384 PNG is copied at its original density.
     * Invalid PNGs, unexpected dimensions, and source overflow fail synchronously.
     */
    fun compose(
        source: ReadmeDemoSource,
        previous: ReadmeDemoSource?,
        screen: ByteArray,
        font: Font,
    ): BufferedImage {
        val preview = requireNotNull(screen.inputStream().use(ImageIO::read)) { "Headless screen is not a PNG." }
        require(preview.width == 512 && preview.height == 384) { "Headless screen must retain its complete 2x viewport." }
        val image = BufferedImage(1200, 900, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            graphics.color = Color(0x0B1018)
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.color = Color(0x121C28)
            graphics.fillRect(0, 0, 648, 900)
            graphics.drawImage(preview, 672, 258, null)
            ReadmeCodePainter.paint(graphics, font, source, previous)
        } finally {
            graphics.dispose()
        }
        return image
    }
}
