package dev.s7a.strata.runtime.minecraft

/**
 * Creates immutable Minecraft Slot locators without retaining a player, menu, or platform.
 *
 * Returned values use structural equality and may be cached or shared across descriptions and threads.
 */
public object MinecraftSlots {
    /**
     * Locates one index in the active player's inventory through the current menu.
     *
     * This works for the ordinary inventory menu and for container menus that expose player-inventory slots.
     *
     * @param index non-negative player-inventory index.
     * @return an immutable reusable locator.
     * @throws IllegalArgumentException when [index] is negative.
     */
    @JvmStatic
    public fun playerInventory(index: Int): MinecraftSlotBinding = create(MinecraftSlotSource.PlayerInventory, index)

    /**
     * Locates one raw slot index in the player's current active menu.
     *
     * This covers vanilla chests, ender chests, furnaces, and custom server-authoritative menus without exposing their version-specific types.
     *
     * @param index non-negative active-menu slot index.
     * @return an immutable reusable locator.
     * @throws IllegalArgumentException when [index] is negative.
     */
    @JvmStatic
    public fun activeMenu(index: Int): MinecraftSlotBinding = create(MinecraftSlotSource.ActiveMenu, index)

    private fun create(
        source: MinecraftSlotSource,
        index: Int,
    ): MinecraftSlotBinding {
        require(0 <= index) { "Minecraft Slot binding index must be non-negative." }
        return Binding(source, index)
    }

    private data class Binding(
        override val source: MinecraftSlotSource,
        override val index: Int,
    ) : MinecraftSlotBinding
}
