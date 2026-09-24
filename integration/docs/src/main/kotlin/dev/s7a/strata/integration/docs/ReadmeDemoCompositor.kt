package dev.s7a.strata.integration.docs

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Places exact source beside an unmodified complete Minecraft-style headless screen.
 * Each call creates a new caller-owned image and closes its graphics; no presentation headings or captions are painted.
 */
internal object ReadmeDemoCompositor {
    /**
     * Composes a 1320 by 1320 frame; the supplied 512 by 384 PNG is copied at its original density.
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
        val image = BufferedImage(1320, 1320, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF)
            graphics.color = Color(0x0B1018)
            graphics.fillRect(0, 0, image.width, image.height)
            val methods =
                source.lines
                    .joinToString("\n")
                    .split("\n\n")
                    .map(String::lines)
            val panels = listOf(Rectangle(0, 0, 648, 720), Rectangle(0, 744, 648, 576), Rectangle(672, 0, 648, 624))
            require(methods.size == panels.size) { "README demo must show the panel, list, and row methods." }
            methods.zip(panels).forEach { (lines, bounds) ->
                val section = graphics.create(bounds.x, bounds.y, bounds.width, bounds.height) as Graphics2D
                try {
                    section.color = Color(0x121C28)
                    section.fillRect(0, 0, bounds.width, bounds.height)
                    ReadmeCodePainter.paint(section, font, lines, previous)
                } finally {
                    section.dispose()
                }
            }
            graphics.drawImage(preview, 740, 816, null)
        } finally {
            graphics.dispose()
        }
        return image
    }
}
