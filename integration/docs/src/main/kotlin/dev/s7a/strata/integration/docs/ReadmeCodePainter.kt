package dev.s7a.strata.integration.docs

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D

/**
 * Draws the small compiled Kotlin excerpt with lexical colors and changed-line emphasis.
 * All painting is synchronous on caller-owned graphics; it neither edits source nor retains the graphics.
 */
internal object ReadmeCodePainter {
    private val tokens = Regex("""("(?:[^"\\]|\\.)*"|\b\d+\b|\b[A-Za-z_][A-Za-z_0-9]*\b)""")
    private val keywords = setOf("val", "var", "fun", "private", "internal", "return", "if", "else")
    private val plain = Color(0xCCD7E5)
    private val accent = Color(0x68D5EB)

    /**
     * Paints the complete source with compact indentation and rejects horizontal or vertical overflow before publication.
     * [previous] determines inserted or changed lines; null starts with an unhighlighted baseline.
     */
    fun paint(
        graphics: Graphics2D,
        font: Font,
        source: ReadmeDemoSource,
        previous: ReadmeDemoSource?,
    ) {
        graphics.font = font.deriveFont(18f)
        val metrics = graphics.fontMetrics
        val previousLines = previous?.lines?.map(String::trimStart)?.toSet()
        require(source.lines.size <= 36) { "README demo source exceeds the code panel height." }
        source.lines.forEachIndexed { index, original ->
            val content = original.trimStart()
            val line = " ".repeat((original.length - content.length) / 2) + content
            require(metrics.stringWidth(line) <= 612) { "README demo source exceeds the code panel width: $line" }
            val baseline = 48 + index * 24
            val changed = previousLines != null && line.isNotBlank() && line.trimStart() !in previousLines
            if (changed) {
                graphics.color = Color(0x203E50)
                graphics.fillRect(8, baseline - 18, 632, 24)
                graphics.color = accent
                graphics.fillRect(8, baseline - 18, 3, 24)
            }
            graphics.color = plain
            graphics.drawString(line, 20, baseline)
            tokens.findAll(line).forEach { match ->
                val token = match.value
                graphics.color =
                    when {
                        token.startsWith('"') -> Color(0xADD897)
                        token.toIntOrNull() != null -> Color(0xE5BA80)
                        token in keywords -> Color(0xC8A2F0)
                        token.first().isUpperCase() -> accent
                        else -> plain
                    }
                graphics.drawString(token, 20 + metrics.stringWidth(line.take(match.range.first)), baseline)
            }
        }
    }
}
