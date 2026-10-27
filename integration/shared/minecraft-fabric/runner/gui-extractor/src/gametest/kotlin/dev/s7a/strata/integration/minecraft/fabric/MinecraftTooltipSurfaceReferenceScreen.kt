package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.tooltip.TooltipRenderUtil
import net.minecraft.network.chat.Component

/**
 * Native tooltip-surface oracle borrowing the game's renderer for one explicit content rectangle.
 * The black background isolates sprite pixels from the game world without reproducing nine-slice geometry.
 */
internal class MinecraftTooltipSurfaceReferenceScreen(
    private val content: IntRect,
) : Screen(Component.empty()) {
    override fun extractRenderState(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        graphics.fill(0, 0, width, height, 0xFF000000.toInt())
        TooltipRenderUtil.extractTooltipBackground(graphics, content.left, content.top, content.width, content.height, null)
    }

    override fun isPauseScreen(): Boolean = false
}
