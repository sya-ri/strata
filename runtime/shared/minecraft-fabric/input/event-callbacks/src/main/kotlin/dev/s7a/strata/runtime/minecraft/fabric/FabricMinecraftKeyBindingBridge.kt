package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.KeyMapping
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo

/**
 * Adapts captured key and mouse values to the compiler-selected native key-binding API.
 */
internal object FabricMinecraftKeyBindingBridge {
    /**
     * Matches one native key press; modifier interpretation belongs to the selected native API.
     */
    fun matches(
        binding: KeyMapping,
        key: Int,
        scanCode: Int,
        modifiers: Int,
    ): Boolean = binding.matches(KeyEvent(key, scanCode, modifiers))

    /**
     * Matches captured native mouse state without retaining an event or binding.
     */
    fun matchesMouse(
        binding: KeyMapping,
        button: Int,
        modifiers: Int,
    ): Boolean = binding.matchesMouse(MouseButtonEvent(0.0, 0.0, MouseButtonInfo(button, modifiers)))
}
