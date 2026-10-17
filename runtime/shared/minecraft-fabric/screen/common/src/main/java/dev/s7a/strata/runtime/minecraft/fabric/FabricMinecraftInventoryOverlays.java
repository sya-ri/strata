package dev.s7a.strata.runtime.minecraft.fabric;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.world.item.ItemStack;

/**
 * Submits current inventory state in carried-item then hovered-tooltip order.
 *
 * <p>Both native GUI families use this client-thread boundary. Each presenter receives a detached stack, and no callback or stack is retained after the call.</p>
 */
final class FabricMinecraftInventoryOverlays {
    private FabricMinecraftInventoryOverlays() {}

    /**
     * Reads each overlay's current state at its submission boundary and leaves native drawing and tooltip eligibility to the presenters.
     * The sources validate their bridge owner on every read, so native item presentation cannot leave a stale hover or menu for the tooltip.
     *
     * @param carriedSource authoritative carried stack, or null when its player is absent.
     * @param hoveredSource synchronized hover snapshot, or null when no binding is hit or its player is absent.
     * @param renderTooltip native tooltip presenter; its second argument borrows the current carried stack for eligibility.
     * @param renderCarried native carried-item presenter, invoked before the tooltip presenter.
     */
    static void render(Supplier<ItemStack> carriedSource, Supplier<ItemStack> hoveredSource, BiConsumer<ItemStack, ItemStack> renderTooltip, Consumer<ItemStack> renderCarried) {
        presentCarried(carriedSource.get(), renderCarried);
        ItemStack hovered = hoveredSource.get();
        if (hovered == null || hovered.isEmpty()) return;
        ItemStack currentCarried = carriedSource.get();
        if (currentCarried != null) {
            renderTooltip.accept(hovered.copy(), currentCarried);
        }
    }

    private static void presentCarried(ItemStack carried, Consumer<ItemStack> renderCarried) {
        if (carried == null || carried.isEmpty()) return;
        renderCarried.accept(carried.copy());
    }
}
