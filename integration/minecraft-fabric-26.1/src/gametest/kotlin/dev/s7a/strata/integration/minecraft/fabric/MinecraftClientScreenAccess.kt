package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Isolates the native GUI access used by client-thread loaded acceptance tests.
 */
internal object MinecraftClientScreenAccess {
    /**
     * Changes HUD visibility while preserving screen rendering; returns the state to restore.
     */
    fun exchangeHudHidden(
        minecraft: Minecraft,
        hidden: Boolean,
    ): Boolean {
        val previous = minecraft.options.hideGui
        minecraft.options.hideGui = hidden
        return previous
    }

    /**
     * Returns the borrowed active screen, if present.
     */
    fun currentScreen(minecraft: Minecraft): Screen? = minecraft.screen

    /**
     * Observes whether a native overlay remains active without changing client state.
     */
    fun hasOverlay(minecraft: Minecraft): Boolean = minecraft.overlay != null

    /**
     * Replaces the active screen on its client thread.
     */
    fun setScreen(
        minecraft: Minecraft,
        screen: Screen?,
    ) {
        minecraft.setScreen(screen)
    }
}
