package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Extracts the isolated numeric scene through the unchanged native screen text path.
 * The caller owns and closes the borrowed font session only after removing this screen.
 */
internal class MinecraftNativeNumericFontScreen(
    private val fonts: MinecraftNativeNumericFonts,
) : Screen(Component.literal("Independent numeric font oracle")) {
    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), MinecraftFontParityFixture.background)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        fonts.text(graphics)
    }
}
