package dev.s7a.strata.runtime.minecraft.fabric;

import dev.s7a.strata.input.InputResult;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Owns native menu pointer gestures for one client-thread inventory bridge.
 * Native slots define double-click and quick-craft identity; retained bindings only own their participation.
 * Gesture storage is bounded by the active menu's visited slots and their live bindings, and target or terminal release drops those references.
 * Native mouse events, key matching, creative capability and click-enum encoding remain in their owning adapter.
 *
 * @param <I> adapter-owned native dispatch metadata
 * @param <H> retained slot binding owned by the adapter
 */
abstract class FabricMinecraftInventoryPointerInput<I, H> extends FabricMinecraftInputTransaction<I, H> {
    private final Map<Slot, Set<H>> quickCraftSlots = new LinkedHashMap<>();
    private boolean quickCrafting;
    private boolean skipNextRelease;
    private int quickCraftButton;
    private FabricMinecraftQuickCraftMode quickCraftMode = FabricMinecraftQuickCraftMode.DISTRIBUTE;
    private boolean pendingDoubleClick;
    private Slot lastClickSlot;
    private H lastClickBinding;
    private ItemStack lastQuickMoved = ItemStack.EMPTY;

    /** Resolves one usable binding into the current generation's native menu slot. */
    abstract Slot resolveSlot(H binding);

    /** Borrows the active player after the adapter verifies owner-thread usability. */
    abstract LocalPlayer requirePlayer();

    /** Borrows the active menu and rejects a changed bound-container generation. */
    abstract AbstractContainerMenu activeMenu();

    /** Applies native hotbar/offhand matching for the current unsupported pointer press. */
    abstract boolean checkHotbarMouse(H binding);

    /** Encodes and submits one native menu action through the owning client game mode. */
    abstract void clickRaw(int slot, int button, FabricMinecraftInventoryAction action);

    /** Starts native pointer handling after the adapter decodes the event's button and modifier state. */
    final InputResult pressInput(H binding, int button, boolean doubleClick, boolean shift, boolean creativePick) {
        Slot slot = resolveSlot(binding);
        FabricMinecraftQuickCraftMode mode = FabricMinecraftQuickCraftMode.fromButton(button, creativePick);
        pendingDoubleClick = doubleClick && mode == FabricMinecraftQuickCraftMode.DISTRIBUTE && lastClickSlot == slot;
        lastClickSlot = slot;
        lastClickBinding = binding;
        skipNextRelease = false;
        if (mode == null) {
            if (checkHotbarMouse(binding)) {
                skipNextRelease = true;
                return InputResult.Consumed;
            }
            return InputResult.Ignored;
        }
        if (quickCrafting) return InputResult.Consumed;
        ItemStack carried = activeMenu().getCarried();
        if (carried.isEmpty()) {
            FabricMinecraftInventoryAction action;
            if (creativePick) {
                action = FabricMinecraftInventoryAction.CLONE;
            } else {
                action = shift ? FabricMinecraftInventoryAction.QUICK_MOVE : FabricMinecraftInventoryAction.PICKUP;
            }
            if (action == FabricMinecraftInventoryAction.QUICK_MOVE) {
                ItemStack item = slot.getItem();
                lastQuickMoved = item.isEmpty() ? ItemStack.EMPTY : item.copy();
            }
            clickRaw(slot.index, button, action);
            skipNextRelease = true;
            return InputResult.Consumed;
        }
        quickCraftButton = button;
        quickCraftSlots.clear();
        quickCraftMode = mode;
        quickCrafting = true;
        return InputResult.Consumed;
    }

    /** Adds an eligible native slot once while retaining each live binding that contributed that slot. */
    final InputResult dragInput(H binding) {
        if (quickCrafting == false) return InputResult.Ignored;
        Slot slot = resolveSlot(binding);
        ItemStack carried = activeMenu().getCarried();
        if (carried.isEmpty() == false
                && (quickCraftSlots.containsKey(slot) || quickCraftSlots.size() < carried.getCount() || quickCraftMode == FabricMinecraftQuickCraftMode.FILL)
                && AbstractContainerMenu.canItemQuickReplace(slot, carried, true)
                && slot.mayPlace(carried)
                && activeMenu().canDragTo(slot)) {
            quickCraftSlots.computeIfAbsent(slot, ignored -> new LinkedHashSet<>()).add(binding);
        }
        return InputResult.Consumed;
    }

    /** Completes a supported release, preserving native pick-item behavior with an occupied cursor. */
    final InputResult releaseInput(H binding, int button, boolean shift, boolean pick) {
        if (FabricMinecraftQuickCraftMode.fromButton(button, false) == null
                && pick == false && quickCrafting == false && skipNextRelease == false) return InputResult.Ignored;
        finishRelease(binding, button, shift, pick);
        return InputResult.Consumed;
    }

    /** Completes a release over a bound slot or after host dispatch did not hit a slot. */
    final void finishRelease(H binding, int button, boolean shift, boolean pick) {
        if (pendingDoubleClick && binding != null && FabricMinecraftQuickCraftMode.fromButton(button, false) == FabricMinecraftQuickCraftMode.DISTRIBUTE
                && activeMenu().canTakeItemForPickAll(ItemStack.EMPTY, resolveSlot(binding))) {
            if (shift && lastQuickMoved.isEmpty() == false) {
                quickMoveMatching(binding, button);
            } else {
                click(binding, button, FabricMinecraftInventoryAction.PICKUP_ALL);
            }
            pendingDoubleClick = false;
            resetQuickCraft();
            return;
        }
        if (quickCrafting && quickCraftButton != button) {
            resetQuickCraft();
            skipNextRelease = true;
            return;
        }
        if (skipNextRelease) {
            skipNextRelease = false;
            resetQuickCraft();
            return;
        }
        if (quickCrafting && quickCraftSlots.isEmpty() == false) {
            sendQuickCraft();
        } else if (binding != null && activeMenu().getCarried().isEmpty() == false) {
            if (pick) {
                click(binding, button, FabricMinecraftInventoryAction.CLONE);
            } else {
                click(binding, button, shift ? FabricMinecraftInventoryAction.QUICK_MOVE : FabricMinecraftInventoryAction.PICKUP);
            }
        }
        resetQuickCraft();
    }

    /** Submits a native action for the current slot behind one usable retained binding. */
    final void click(H binding, int button, FabricMinecraftInventoryAction action) {
        clickRaw(resolveSlot(binding).index, button, action);
    }

    /** Drops pointer identity when a native press did not hit any retained inventory binding. */
    final void missPointerPress() {
        lastClickSlot = null;
        lastClickBinding = null;
        pendingDoubleClick = false;
    }

    /** Removes a disposed binding's gesture participation while preserving other views of the same native slot. */
    final void releasePointerTarget(H binding) {
        quickCraftSlots.values().removeIf(contributors -> {
            contributors.remove(binding);
            return contributors.isEmpty();
        });
        if (lastClickBinding == binding) missPointerPress();
    }

    /** Drops every gesture-owned reference when the adapter closes; native client access is unnecessary. */
    final void releasePointerState() {
        missPointerPress();
        resetQuickCraft();
        skipNextRelease = false;
        lastQuickMoved = ItemStack.EMPTY;
    }

    private void sendQuickCraft() {
        int mode = quickCraftMode.nativeCode();
        clickRaw(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, mode), FabricMinecraftInventoryAction.QUICK_CRAFT);
        for (Slot slot : quickCraftSlots.keySet()) {
            clickRaw(slot.index, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, mode), FabricMinecraftInventoryAction.QUICK_CRAFT);
        }
        clickRaw(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, mode), FabricMinecraftInventoryAction.QUICK_CRAFT);
    }

    private void quickMoveMatching(H binding, int button) {
        Slot reference = resolveSlot(binding);
        LocalPlayer player = requirePlayer();
        for (Slot slot : activeMenu().slots) {
            if (slot.mayPickup(player)
                    && slot.hasItem()
                    && slot.container == reference.container
                    && AbstractContainerMenu.canItemQuickReplace(slot, lastQuickMoved, true)) {
                clickRaw(slot.index, button, FabricMinecraftInventoryAction.QUICK_MOVE);
            }
        }
    }

    private void resetQuickCraft() {
        quickCrafting = false;
        quickCraftSlots.clear();
    }
}
