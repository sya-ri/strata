package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Native Minecraft 26.2 text extraction oracle for the shared original-font scene.
 * It borrows the game's font manager and never consumes a portable draw command or raster.
 */
internal class MinecraftNativeFontScreen : Screen(Component.literal("Independent native font oracle")) {
    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        MinecraftNativeFontScene.background(graphics)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        MinecraftNativeFontScene.text(graphics)
    }
}
