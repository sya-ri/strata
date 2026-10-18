package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Renders carried inventory items through the pose-stack depth model used by Minecraft 1.21.5.
 *
 * <p>The supplied graphics context, font, and stack are borrowed only for the synchronous client-thread call.
 */
final class FabricMinecraftCarriedItemRenderer {
    private static final float CARRIED_ITEM_DEPTH = 232.0F;

    private FabricMinecraftCarriedItemRenderer() {}

    /**
     * Renders a carried stack above the rest of the screen and restores the incoming pose afterward.
     *
     * @param graphics active client-thread graphics context.
     * @param font active Minecraft font used for stack decorations.
     * @param stack immutable snapshot to render.
     * @param x destination left edge in GUI pixels.
     * @param y destination top edge in GUI pixels.
     */
    static void render(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, CARRIED_ITEM_DEPTH);
        try {
            graphics.renderItem(stack, x, y);
            graphics.renderItemDecorations(font, stack, x, y);
        } finally {
            graphics.pose().popPose();
        }
    }
}
