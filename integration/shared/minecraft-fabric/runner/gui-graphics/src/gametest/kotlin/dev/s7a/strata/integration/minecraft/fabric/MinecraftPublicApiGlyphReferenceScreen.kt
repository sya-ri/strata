package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Draws the public scene's two labels directly with the loaded native font, independently of Strata layout and rasterization.
 * The caller waits for [rendered] before capturing the reference and owns the screen's client-thread lifetime.
 */
internal class MinecraftPublicApiGlyphReferenceScreen(
    private val release: String,
) : Screen(Component.literal("Public API native glyph reference")) {
    /**
     * Whether the native labels have reached a complete render call.
     */
    var rendered: Boolean = false
        private set

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, width, height, 0xFF000000.toInt())
        val title = "Minecraft $release"
        val titleTop = (height - (font.lineHeight + 8 + 20)) / 2
        // The title is centered within the 150-pixel column before the column is centered in the viewport.
        val titleLeft = (width - 150) / 2 + (150 - font.width(title)) / 2
        graphics.drawString(font, title, titleLeft, titleTop, 0xFFFFFFFF.toInt(), true)
        val label = "Loaded public API"
        graphics.drawString(font, label, width / 2 - font.width(label) / 2, titleTop + font.lineHeight + 8 + 6, 0xFFFFFFFF.toInt(), true)
        rendered = true
    }
}
