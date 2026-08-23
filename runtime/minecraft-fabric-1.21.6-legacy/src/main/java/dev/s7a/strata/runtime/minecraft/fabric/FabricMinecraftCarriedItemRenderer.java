package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

/**
 * Renders carried inventory items through the stratum model used by Minecraft 1.21.6 and later supported remapped releases.
 *
 * <p>The supplied graphics context, font, and stack are borrowed only for the synchronous client-thread call.
 */
final class FabricMinecraftCarriedItemRenderer {
    private FabricMinecraftCarriedItemRenderer() {}

    /**
     * Renders a carried stack in Minecraft's next GUI stratum.
     *
     * @param graphics active client-thread graphics context.
     * @param font active Minecraft font used for stack decorations.
     * @param stack immutable snapshot to render.
     * @param x destination left edge in GUI pixels.
     * @param y destination top edge in GUI pixels.
     */
    static void render(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.nextStratum();
        graphics.renderItem(stack, x, y);
        graphics.renderItemDecorations(font, stack, x, y);
    }
}
