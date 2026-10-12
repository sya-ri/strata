package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.SimpleContainer
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Independent native tooltip oracle using the game's original container-screen implementation.
 *
 * The private menu and copied stacks belong only to this client-thread fixture; no server inventory is mutated.
 * The black plane isolates the native final item/tooltip stratum from the production scene's masked Slot content.
 */
internal class MinecraftSlotTooltipReferenceScreen(
    stack: ItemStack?,
    carriedSnapshot: ItemStack,
) : AbstractContainerScreen<InventoryMenu>(
        InventoryMenu(checkNotNull(Minecraft.getInstance().player).inventory, false, checkNotNull(Minecraft.getInstance().player)),
        checkNotNull(Minecraft.getInstance().player).inventory,
        Component.empty(),
    ) {
    /**
     * Completed native submissions observed only on the client thread.
     */
    internal var frames: Long = 0L
        private set

    init {
        menu.carried = carriedSnapshot.copy()
        hoveredSlot = stack?.let { Slot(SimpleContainer(it.copy()), 0, 0, 0) }
    }

    override fun render(
        graphics: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
    ) {
        graphics.fill(0, 0, width, height, black)
        super.renderCarriedItem(graphics, mouseX, mouseY)
        super.renderTooltip(graphics, mouseX, mouseY)
        frames += 1L
    }

    override fun renderBg(
        graphics: GuiGraphics,
        delta: Float,
        mouseX: Int,
        mouseY: Int,
    ) = Unit

    override fun removed() = Unit

    private companion object {
        private val black = 0xFF000000.toInt()
    }
}
