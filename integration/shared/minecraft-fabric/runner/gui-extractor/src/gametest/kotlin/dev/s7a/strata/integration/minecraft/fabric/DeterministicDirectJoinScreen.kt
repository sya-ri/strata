package dev.s7a.strata.integration.minecraft.fabric

import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.DirectJoinServerScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.network.chat.Component

/**
 * Direct Join fixture whose background excludes nondeterministic menu layers.
 * The initial address is shared with the headless reference or native-input focus scenario.
 */
internal class DeterministicDirectJoinScreen(
    address: String,
) : DirectJoinServerScreen(
        EmptyParentScreen(),
        BooleanConsumer { _ -> },
        ServerData("Strata parity", address, ServerData.Type.OTHER),
    ) {
    override fun extractBackground(
        graphics: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        extractMenuBackground(graphics)
    }

    private class EmptyParentScreen : Screen(Component.empty())
}
