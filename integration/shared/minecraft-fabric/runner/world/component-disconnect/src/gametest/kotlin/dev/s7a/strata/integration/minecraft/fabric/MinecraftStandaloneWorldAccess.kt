package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.screens.GenericMessageScreen
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen
import net.minecraft.network.chat.Component
import net.minecraft.world.level.GameRules

/**
 * Client-thread native world-creation and disconnect boundaries for the component-aware disconnect API.
 */
internal object MinecraftStandaloneWorldAccess {
    /**
     * Opens native world creation with the current screen as its return destination.
     */
    fun open(minecraft: Minecraft) {
        CreateWorldScreen.openFresh(minecraft, minecraft.screen)
    }

    /**
     * Disables ambient mob spawning in the disposable flat world.
     */
    fun configure(screen: CreateWorldScreen) {
        screen.uiState.gameRules
            .getRule(GameRules.RULE_DOMOBSPAWNING)
            .set(false, null)
    }

    /**
     * Invokes the selected native create-world button.
     */
    fun press(button: AbstractButton) {
        button.onPress()
    }

    /**
     * Saves and disconnects the loaded world before its storage is released.
     */
    fun disconnect(minecraft: Minecraft) {
        minecraft.level?.disconnect(Component.translatable("menu.savingLevel"))
        minecraft.disconnect(GenericMessageScreen(Component.translatable("menu.savingLevel")), false)
    }
}
