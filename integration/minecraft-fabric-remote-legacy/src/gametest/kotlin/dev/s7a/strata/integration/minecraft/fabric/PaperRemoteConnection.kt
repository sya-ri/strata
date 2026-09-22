package dev.s7a.strata.integration.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress

/**
 * Starts the native exact-version connection on the client thread without a proxy or protocol translator.
 */
internal fun connectPaperTest(
    minecraft: Minecraft,
    address: String,
) {
    ConnectScreen.startConnecting(TitleScreen(), minecraft, ServerAddress.parseString(address), ServerData("Strata acceptance", address, false), false)
}

/**
 * Ends the fixture's native level and connection before returning to the title screen.
 */
internal fun disconnectPaperTest(minecraft: Minecraft) {
    minecraft.level?.disconnect()
    minecraft.clearLevel(TitleScreen())
}
