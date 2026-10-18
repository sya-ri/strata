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
    @JvmStatic
    fun initialize(
        client: Minecraft,
        screen: Screen,
    ) {
        screen.init(client.window.guiScaledWidth, client.window.guiScaledHeight)
    }

    /**
     * Reads the native F1 visibility flag.
     */
    @JvmStatic
    fun hudHidden(client: Minecraft): Boolean = client.gui.hud.isHidden

    /**
     * Reports loading overlays that prevent forwarding input into gameplay.
     */
    @JvmStatic
    fun hasOverlay(client: Minecraft): Boolean = client.gui.overlay() != null

    /**
     * Borrows the currently presented screen, or null when gameplay is visible.
     */
    @JvmStatic
    fun currentScreen(client: Minecraft): Screen? = client.gui.screen()

    /**
     * Replaces the currently presented screen without taking ownership of its lifetime.
     */
    @JvmStatic
    fun setScreen(
        client: Minecraft,
        screen: Screen?,
    ) {
        client.gui.setScreen(screen)
    }
}
