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
     * Resolves a raw slot index in the player's current active menu.
     */
    ActiveMenu,
}
