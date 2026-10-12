package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.item.ItemStack;

/**
 * Delegates item tooltip content, settings, images, style, and viewport positioning to the target's native GUI.
 *
 * <p>Carried-item eligibility follows this native API family. Every argument is borrowed for one client-thread call.</p>
 */
final class FabricMinecraftSlotTooltipRenderer {
    private FabricMinecraftSlotTooltipRenderer() {}

    /**
     * Presents the hovered snapshot with the same carried-item policy as the native container screen.
     *
     * @param graphics current native GUI context.
     * @param font active font for tooltip lines.
     * @param stack copied hovered item snapshot.
     * @param carried authoritative currently carried stack, used only for eligibility.
     * @param x current pointer x in GUI pixels.
     * @param y current pointer y in GUI pixels.
     */
    static void render(GuiGraphics graphics, Font font, ItemStack stack, ItemStack carried, int x, int y) {
        if (carried.isEmpty() || stack.getTooltipImage().map(ClientTooltipComponent::create).map(ClientTooltipComponent::showTooltipWithItemInHand).orElse(false)) {
            graphics.setTooltipForNextFrame(font, stack, x, y);
        }
    }
}
