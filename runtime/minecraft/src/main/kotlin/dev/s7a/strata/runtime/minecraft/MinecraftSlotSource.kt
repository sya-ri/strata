package dev.s7a.strata.runtime.minecraft

/**
 * Selects the Minecraft menu coordinate system used by a [MinecraftSlotBinding].
 *
 * The value is immutable and may be read from any thread; a version adapter resolves it against its current owner-thread menu only while building or operating a host.
 */
public enum class MinecraftSlotSource {
    /**
     * Resolves an index in the active player's inventory through the current menu.
     */
    PlayerInventory,

    /**
     * Resolves a logical index in the one non-player Container exposed by the active server menu.
     *
     * A menu with no matching slot or with ambiguous matching slots from multiple Containers fails instead of selecting storage by declaration order.
     */
    Container,

    /**
     * Resolves a raw slot index in the player's current active menu.
     */
    ActiveMenu,
}
