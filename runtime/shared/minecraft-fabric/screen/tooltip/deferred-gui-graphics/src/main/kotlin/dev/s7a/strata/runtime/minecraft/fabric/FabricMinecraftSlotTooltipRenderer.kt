package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent
import net.minecraft.world.item.ItemStack

/**
 * Delegates item tooltip content, images, style and placement to the selected native GUI API.
 */
internal object FabricMinecraftSlotTooltipRenderer {
    /**
     * Presents the copied hovered snapshot using the native policy for the currently carried item.
     * Every argument is borrowed only for this client-thread submission.
     */
    fun render(
        graphics: GuiGraphics,
        font: Font,
        stack: ItemStack,
        carried: ItemStack,
        x: Int,
        y: Int,
    ) {
        if (carried.isEmpty ||
            stack.tooltipImage
                .map(ClientTooltipComponent::create)
                .map(ClientTooltipComponent::showTooltipWithItemInHand)
                .orElse(false)
        ) {
            graphics.setTooltipForNextFrame(font, stack, x, y)
        }
    }
}
