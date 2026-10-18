package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Isolates compiler-selected native screen, overlay and HUD visibility access on the client owner.
 */
internal object FabricMinecraftScreenAccess {
    /**
     * Initializes a borrowed HUD wrapper at the current logical viewport.
     */
    fun initialize(
        client: Minecraft,
        screen: Screen,
    ) {
        FabricMinecraftScreenInitializer.initialize(client, screen)
    }

    /**
     * Reads the native F1 visibility flag.
     */
    fun hudHidden(client: Minecraft): Boolean = client.options.hideGui

    /**
     * Reports loading overlays that prevent forwarding input into gameplay.
     */
    fun hasOverlay(client: Minecraft): Boolean = client.overlay != null

    /**
     * Borrows the currently presented screen, or null when gameplay is visible.
     */
    fun currentScreen(client: Minecraft): Screen? = client.screen

    /**
     * Replaces the currently presented screen without taking ownership of its lifetime.
     */
    fun setScreen(
        client: Minecraft,
        screen: Screen?,
    ) {
        client.setScreen(screen)
    }
}
