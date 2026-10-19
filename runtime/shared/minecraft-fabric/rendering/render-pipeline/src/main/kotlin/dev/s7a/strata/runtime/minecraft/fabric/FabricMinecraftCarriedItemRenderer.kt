package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/**
 * Presents carried inventory items above screen content using the selected native depth model.
 */
internal object FabricMinecraftCarriedItemRenderer {
    /**
     * Borrows native drawing arguments for one client-thread call and restores any borrowed pose after failure.
     */
    fun render(
        graphics: GuiGraphics,
        font: Font,
        stack: ItemStack,
        x: Int,
        y: Int,
    ) {
        graphics.nextStratum()
        graphics.renderItem(stack, x, y)
        graphics.renderItemDecorations(font, stack, x, y)
    }
}
