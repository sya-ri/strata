package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.world.item.ItemStack

/**
 * Submits current inventory overlays in carried-item then hovered-tooltip order.
 * Presentation receives detached stacks and retains no source, presenter or native item after return.
 */
internal object FabricMinecraftInventoryOverlays {
    /**
     * Revalidates each source at its submission boundary so carried-item drawing cannot leave a stale hover or menu.
     */
    fun render(
        carriedSource: () -> ItemStack?,
        hoveredSource: () -> ItemStack?,
        renderTooltip: (ItemStack, ItemStack) -> Unit,
        renderCarried: (ItemStack) -> Unit,
    ) {
        val carried = carriedSource()
        if (carried != null && carried.isEmpty.not()) renderCarried(carried.copy())
        val hovered = hoveredSource() ?: return
        if (hovered.isEmpty) return
        val currentCarried = carriedSource() ?: return
        renderTooltip(hovered.copy(), currentCarried)
    }
}
