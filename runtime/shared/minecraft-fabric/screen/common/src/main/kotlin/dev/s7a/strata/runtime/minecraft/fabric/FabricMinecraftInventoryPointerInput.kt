package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.input.InputResult
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack

/**
 * Owns native menu pointer gestures for one client-thread inventory bridge.
 * Native slots define double-click and quick-craft identity; retained bindings only own their participation.
 * Gesture storage is bounded by the active menu's visited slots and their live bindings, and target or terminal release drops those references.
 * Native mouse events, key matching, creative capability and click-enum encoding remain in their owning adapter.
 *
 * @param M immutable mouse event decoded by the owning native adapter
 * @param H retained slot binding owned by the adapter
 */
@Suppress("TooManyFunctions") // Native pointer phases and adapter callbacks share one bounded gesture owner.
internal abstract class FabricMinecraftInventoryPointerInput<M : Any, H : Any> : FabricMinecraftInputTransaction<FabricMinecraftInventoryPointerInput.NativeInput<M, H>, H>() {
    /**
     * Native pointer phase represented by the current transaction.
     */
    enum class InputKind { MOVE, PRESS, DRAG, RELEASE }

    /**
     * Immutable native event metadata, with delivered-target identity owned by the current host dispatch.
     * Mouse metadata is absent only for pointer movement without a native button event.
     */
    class NativeInput<M : Any, H : Any>(
        val kind: InputKind,
        val mouse: M?,
        val button: Int,
        val shift: Boolean,
        val doubleClick: Boolean,
    ) {
        /**
         * Target already delivered during this transaction, or null before native dispatch reaches a binding.
         */
        var delivered: H? = null
    }

    /**
     * Last eligible hover target, borrowed until its binding or platform is released.
     */
    var hovered: H? = null

    /**
     * Terminal platform cutoff observed by the owning adapter's usability checks.
     */
    var closed: Boolean = false

    private val quickCraftSlots: MutableMap<Slot, MutableSet<H>> = linkedMapOf()
    private var quickCrafting = false
    private var skipNextRelease = false
    private var quickCraftButton = 0
    private var quickCraftMode = FabricMinecraftQuickCraftMode.DISTRIBUTE
    private var pendingDoubleClick = false
    private var lastClickSlot: Slot? = null
    private var lastClickBinding: H? = null
    private var lastQuickMoved: ItemStack = ItemStack.EMPTY

    /**
     * Resolves a usable binding into the current generation's native menu slot.
     */
    abstract fun resolveSlot(binding: H): Slot

    /**
     * Borrows the active player after owner-thread usability is verified.
     */
    abstract fun requirePlayer(): LocalPlayer

    /**
     * Borrows the active menu and rejects a changed bound-container generation.
     */
    abstract fun activeMenu(): AbstractContainerMenu

    /**
     * Applies native hotbar and offhand matching for an unsupported pointer press.
     */
    abstract fun checkHotbarMouse(binding: H): Boolean

    /**
     * Encodes and submits one native menu action through the owning client game mode.
     */
    abstract fun clickRaw(
        slot: Int,
        button: Int,
        action: FabricMinecraftInventoryAction,
    )

    /**
     * Matches an event against the current native pick-item key binding.
     */
    abstract fun isPickMouse(mouse: M): Boolean

    /**
     * Matches native pick input and verifies the owning version's creative capability.
     */
    abstract fun isCreativePick(mouse: M): Boolean

    final override fun finishInput(current: NativeInput<M, H>) {
        if (closed) return
        if (current.kind == InputKind.PRESS && current.delivered == null) missPointerPress()
        if (current.kind == InputKind.MOVE || current.kind == InputKind.DRAG) hovered = offeredHover
        if (current.kind == InputKind.RELEASE && current.delivered == null) finishRelease(null, current.button, shift = false, pick = false)
    }

    /**
     * Decodes a bound press only during its matching native transaction.
     */
    fun handlePress(binding: H): InputResult {
        val current = requireInput(InputKind.PRESS)
        return pressInput(binding, current.button, current.doubleClick, current.shift, isCreativePick(checkNotNull(current.mouse)))
    }

    /**
     * Adds a drag target only during its matching native transaction.
     */
    fun handleDrag(binding: H): InputResult {
        requireInput(InputKind.DRAG)
        return dragInput(binding)
    }

    /**
     * Completes a bound release only during its matching native transaction.
     */
    fun handleRelease(binding: H): InputResult {
        val current = requireInput(InputKind.RELEASE)
        return releaseInput(binding, current.button, current.shift, isPickMouse(checkNotNull(current.mouse)))
    }

    /**
     * Borrows metadata for the exact active pointer phase, rejecting unmatched dispatch.
     */
    fun requireInput(expected: InputKind): NativeInput<M, H> {
        val current = input
        check(current != null && current.kind == expected) { "Minecraft Slot input requires the matching native transaction." }
        return current
    }

    /**
     * Starts pointer handling after the adapter decodes button and modifier state.
     */
    fun pressInput(
        binding: H,
        button: Int,
        doubleClick: Boolean,
        shift: Boolean,
        creativePick: Boolean,
    ): InputResult {
        val slot = resolveSlot(binding)
        val mode = FabricMinecraftQuickCraftMode.fromButton(button, creativePick)
        pendingDoubleClick = doubleClick && mode == FabricMinecraftQuickCraftMode.DISTRIBUTE && lastClickSlot === slot
        lastClickSlot = slot
        lastClickBinding = binding
        skipNextRelease = false
        if (mode == null) {
            if (checkHotbarMouse(binding)) {
                skipNextRelease = true
                return InputResult.Consumed
            }
            return InputResult.Ignored
        }
        if (quickCrafting) return InputResult.Consumed
        val carried = activeMenu().carried
        if (carried.isEmpty) {
            val action =
                when {
                    creativePick -> FabricMinecraftInventoryAction.CLONE
                    shift -> FabricMinecraftInventoryAction.QUICK_MOVE
                    else -> FabricMinecraftInventoryAction.PICKUP
                }
            if (action == FabricMinecraftInventoryAction.QUICK_MOVE) {
                val item = slot.item
                lastQuickMoved = if (item.isEmpty) ItemStack.EMPTY else item.copy()
            }
            clickRaw(slot.index, button, action)
            skipNextRelease = true
            return InputResult.Consumed
        }
        quickCraftButton = button
        quickCraftSlots.clear()
        quickCraftMode = mode
        quickCrafting = true
        return InputResult.Consumed
    }

    /**
     * Adds each eligible native slot once, retaining every live binding that contributed it.
     */
    fun dragInput(binding: H): InputResult {
        if (quickCrafting.not()) return InputResult.Ignored
        val slot = resolveSlot(binding)
        val carried = activeMenu().carried
        if (carried.isEmpty) return InputResult.Consumed
        val hasCapacity = quickCraftSlots.containsKey(slot) || quickCraftSlots.size < carried.count || quickCraftMode == FabricMinecraftQuickCraftMode.FILL
        if (hasCapacity.not()) return InputResult.Consumed
        if (AbstractContainerMenu.canItemQuickReplace(slot, carried, true) &&
            slot.mayPlace(carried) &&
            activeMenu().canDragTo(slot)
        ) {
            quickCraftSlots.getOrPut(slot) { linkedSetOf() }.add(binding)
        }
        return InputResult.Consumed
    }

    /**
     * Handles a bound release, including cancellation by another button during an active gesture.
     */
    fun releaseInput(
        binding: H,
        button: Int,
        shift: Boolean,
        pick: Boolean,
    ): InputResult {
        val supportedButton = FabricMinecraftQuickCraftMode.fromButton(button, false) != null || pick
        if (supportedButton.not() && quickCrafting.not() && skipNextRelease.not()) return InputResult.Ignored
        finishRelease(binding, button, shift, pick)
        return InputResult.Consumed
    }

    /**
     * Completes a release over a bound slot or after host dispatch missed every slot.
     */
    fun finishRelease(
        binding: H?,
        button: Int,
        shift: Boolean,
        pick: Boolean,
    ) {
        if (collectDoubleClick(binding, button, shift)) return
        if (quickCrafting && quickCraftButton != button) {
            resetQuickCraft()
            skipNextRelease = true
            return
        }
        if (skipNextRelease) {
            skipNextRelease = false
            resetQuickCraft()
            return
        }
        if (quickCrafting && quickCraftSlots.isEmpty().not()) {
            sendQuickCraft()
        } else if (binding != null && activeMenu().carried.isEmpty.not()) {
            val action =
                when {
                    pick -> FabricMinecraftInventoryAction.CLONE
                    shift -> FabricMinecraftInventoryAction.QUICK_MOVE
                    else -> FabricMinecraftInventoryAction.PICKUP
                }
            click(binding, button, action)
        }
        resetQuickCraft()
    }

    /**
     * Submits an action for the current native slot behind a usable retained binding.
     */
    fun click(
        binding: H,
        button: Int,
        action: FabricMinecraftInventoryAction,
    ) {
        clickRaw(resolveSlot(binding).index, button, action)
    }

    /**
     * Drops double-click identity after a press misses every retained inventory binding.
     */
    fun missPointerPress() {
        lastClickSlot = null
        lastClickBinding = null
        pendingDoubleClick = false
    }

    /**
     * Removes a disposed binding's participation while preserving other views of the same native slot.
     */
    fun releasePointerTarget(binding: H) {
        quickCraftSlots.values.removeAll { contributors ->
            contributors.remove(binding)
            contributors.isEmpty()
        }
        if (lastClickBinding === binding) missPointerPress()
        if (hovered === binding) hovered = null
    }

    /**
     * Drops all gesture references at terminal release without accessing the native client.
     */
    fun releasePointerState() {
        closed = true
        hovered = null
        missPointerPress()
        resetQuickCraft()
        skipNextRelease = false
        lastQuickMoved = ItemStack.EMPTY
    }

    private fun sendQuickCraft() {
        val mode = quickCraftMode.nativeCode()
        clickRaw(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_START, mode), FabricMinecraftInventoryAction.QUICK_CRAFT)
        for (slot in quickCraftSlots.keys) {
            clickRaw(slot.index, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_CONTINUE, mode), FabricMinecraftInventoryAction.QUICK_CRAFT)
        }
        clickRaw(AbstractContainerMenu.SLOT_CLICKED_OUTSIDE, AbstractContainerMenu.getQuickcraftMask(AbstractContainerMenu.QUICKCRAFT_HEADER_END, mode), FabricMinecraftInventoryAction.QUICK_CRAFT)
    }

    private fun quickMoveMatching(
        binding: H,
        button: Int,
    ) {
        val reference = resolveSlot(binding)
        val player = requirePlayer()
        for (slot in activeMenu().slots) {
            val eligible = slot.mayPickup(player) && slot.hasItem() && slot.container === reference.container
            if (eligible && AbstractContainerMenu.canItemQuickReplace(slot, lastQuickMoved, true)) {
                clickRaw(slot.index, button, FabricMinecraftInventoryAction.QUICK_MOVE)
            }
        }
    }

    private fun collectDoubleClick(
        binding: H?,
        button: Int,
        shift: Boolean,
    ): Boolean {
        if (pendingDoubleClick.not() || binding == null) return false
        if (FabricMinecraftQuickCraftMode.fromButton(button, false) != FabricMinecraftQuickCraftMode.DISTRIBUTE) return false
        if (activeMenu().canTakeItemForPickAll(ItemStack.EMPTY, resolveSlot(binding)).not()) return false
        if (shift && lastQuickMoved.isEmpty.not()) {
            quickMoveMatching(binding, button)
        } else {
            click(binding, button, FabricMinecraftInventoryAction.PICKUP_ALL)
        }
        pendingDoubleClick = false
        resetQuickCraft()
        return true
    }

    private fun resetQuickCraft() {
        quickCrafting = false
        quickCraftSlots.clear()
    }
}
