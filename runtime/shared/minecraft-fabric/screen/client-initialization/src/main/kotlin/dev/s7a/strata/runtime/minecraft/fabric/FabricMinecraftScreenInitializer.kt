package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Initializes a borrowed screen at the current logical viewport on the client thread.
 */
internal object FabricMinecraftScreenInitializer {
    /**
     * Supplies the exact native initialization signature selected by this adapter.
     */
    fun initialize(
        client: Minecraft,
        screen: Screen,
    ) {
        screen.init(client, client.window.guiScaledWidth, client.window.guiScaledHeight)
    }
}
