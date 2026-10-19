package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.KeyMapping

/**
 * Adapts captured key and mouse values to the compiler-selected native key-binding API.
 */
internal object FabricMinecraftKeyBindingBridge {
    /**
     * Matches one native key press; modifier interpretation belongs to the selected native API.
     */
    @Suppress("UnusedParameter") // The primitive native API has no modifier argument; adapter callers share one signature.
    fun matches(
        binding: KeyMapping,
        key: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean = binding.matches(key, scanCode)

    /**
     * Matches captured native mouse state without retaining an event or binding.
     */
    @Suppress("UnusedParameter") // The primitive native API matches mouse buttons without modifier state.
    fun matchesMouse(
        binding: KeyMapping,
        button: Int,
        modifiers: Int,
    ): Boolean = binding.matchesMouse(button)
}
