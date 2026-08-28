package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Draws the readability literals through Minecraft's ordinary default-font Component path on the client thread.
 * The screen owns no font, texture, or portable data; it only borrows the active native renderer.
 */
internal class MinecraftNativeTextReadabilityScreen : Screen(Component.literal("Native default font readability")) {
    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        graphics.fill(0, 0, graphics.guiWidth(), graphics.guiHeight(), MinecraftTextReadabilityScene.BACKGROUND)
    }

    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        MinecraftTextReadabilityScene.rows.forEach { row ->
            graphics.text(
                Minecraft.getInstance().font,
                Component.literal(row.text),
                MinecraftTextReadabilityScene.LEFT,
                row.top,
                MinecraftTextReadabilityScene.FOREGROUND,
                false,
            )
        }
    }
}
