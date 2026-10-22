package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Borrows the legacy client's screen, overlay and HUD state on its client thread.
 */
internal object MinecraftClientScreenAccess {
    /**
     * Returns the screen currently borrowing the client.
     */
    fun currentScreen(minecraft: Minecraft): Screen? = minecraft.screen

    /**
     * Observes whether native loading is covering the active screen.
     */
    fun hasOverlay(minecraft: Minecraft): Boolean = minecraft.overlay != null

    /**
     * Replaces the borrowed client's screen through its native lifecycle.
     */
    fun setScreen(
        minecraft: Minecraft,
        screen: Screen?,
    ) {
        minecraft.setScreen(screen)
    }

    /**
     * Changes HUD visibility and returns the value the caller must restore.
     */
    fun exchangeHudHidden(
        minecraft: Minecraft,
        hidden: Boolean,
    ): Boolean {
        val previous = minecraft.options.hideGui
        minecraft.options.hideGui = hidden
        return previous
    }
}
