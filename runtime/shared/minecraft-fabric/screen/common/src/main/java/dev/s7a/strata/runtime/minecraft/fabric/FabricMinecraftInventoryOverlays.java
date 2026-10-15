package dev.s7a.strata.runtime.minecraft.fabric;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.world.item.ItemStack;

/**
 * Submits one frame's borrowed inventory state in carried-item then hovered-tooltip order.
 *
 * <p>Both native GUI families use this client-thread boundary. Each presenter receives a detached stack, and no callback or stack is retained after the call.</p>
 */
final class FabricMinecraftInventoryOverlays {
    private FabricMinecraftInventoryOverlays() {}

    /**
     * Copies populated stacks at submission and leaves version-specific drawing and tooltip eligibility to the presenters.
     *
     * @param carried authoritative carried stack for this submission.
     * @param hovered current synchronized hover snapshot, or null when no binding is hit.
     * @param tooltip native tooltip presenter; its second argument borrows the current carried stack for eligibility.
     * @param item native carried-item presenter, invoked before the tooltip presenter.
     */
    static void render(ItemStack carried, ItemStack hovered, BiConsumer<ItemStack, ItemStack> tooltip, Consumer<ItemStack> item) {
        if (carried.isEmpty() == false) {
            item.accept(carried.copy());
        }
        if (hovered != null && hovered.isEmpty() == false) {
            tooltip.accept(hovered.copy(), carried);
        }
    }
}
