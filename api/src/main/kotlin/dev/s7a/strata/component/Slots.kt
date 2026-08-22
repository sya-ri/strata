package dev.s7a.strata.component

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Creates immutable synchronized slot locators without retaining a player, menu, or platform.
 *
 * Returned values use structural equality and may be cached or shared across descriptions and threads.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object Slots {
    /**
     * Locates one index in the active player's inventory through the current menu.
     *
     * @param index non-negative player-inventory index.
     * @return an immutable reusable binding.
     * @throws IllegalArgumentException when [index] is negative.
     */
    @JvmStatic
    public fun playerInventory(index: Int): SlotBinding = create(SlotBinding.Source.PlayerInventory, index)

    /**
     * Locates one logical slot in the non-player container exposed by the current server-owned menu.
     *
     * @param index non-negative logical container index.
     * @return an immutable reusable binding.
     * @throws IllegalArgumentException when [index] is negative.
     */
    @JvmStatic
    public fun container(index: Int): SlotBinding = create(SlotBinding.Source.Container, index)

    /**
     * Locates one raw slot in the player's current active menu.
     *
     * @param index non-negative active-menu index.
     * @return an immutable reusable binding.
     * @throws IllegalArgumentException when [index] is negative.
     */
    @JvmStatic
    public fun activeMenu(index: Int): SlotBinding = create(SlotBinding.Source.ActiveMenu, index)

    private fun create(
        source: SlotBinding.Source,
        index: Int,
    ): SlotBinding {
        require(0 <= index) { "Slot binding index must be non-negative." }
        return Binding(source, index)
    }

    private data class Binding(
        override val source: SlotBinding.Source,
        override val index: Int,
    ) : SlotBinding
}
