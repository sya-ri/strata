package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.network.chat.Component
import net.minecraft.world.level.gamerules.GameRules

/**
 * Client-thread native world-creation and disconnect boundaries for the typed game-rule API.
 */
internal object MinecraftStandaloneWorldAccess {
    /**
     * Opens native world creation with the current screen as its return destination.
     */
    fun open(minecraft: Minecraft) {
        val previous = minecraft.screen
        CreateWorldScreen.openFresh(minecraft) { minecraft.setScreen(previous) }
    }

    /**
     * Keeps time, weather, spawning and respawn positions deterministic in the disposable flat world.
     */
    fun configure(screen: CreateWorldScreen) {
        screen.uiState.gameRules.set(GameRules.ADVANCE_TIME, false, null)
        screen.uiState.gameRules.set(GameRules.ADVANCE_WEATHER, false, null)
        screen.uiState.gameRules.set(GameRules.SPAWN_MOBS, false, null)
        screen.uiState.gameRules.set(GameRules.RESPAWN_RADIUS, 0, null)
    }

    /**
     * Invokes the selected native create-world button.
     */
    fun press(button: AbstractButton) {
        button.onPress(MouseButtonInfo(0, 0))
    }

    /**
     * Saves and disconnects the loaded world before its storage is released.
     */
    fun disconnect(minecraft: Minecraft) {
        minecraft.level?.disconnect(Component.translatable("menu.savingLevel"))
        minecraft.disconnect(GenericMessageScreen(Component.translatable("menu.savingLevel")), false)
    }

    /**
     * Reports whether all native world sections have completed rendering.
     */
    fun rendered(minecraft: Minecraft): Boolean = minecraft.levelRenderer.hasRenderedAllSections()
}
