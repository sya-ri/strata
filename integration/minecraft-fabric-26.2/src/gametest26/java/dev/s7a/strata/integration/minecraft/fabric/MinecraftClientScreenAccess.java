package dev.s7a.strata.integration.minecraft.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Isolates the 26.2 GUI holder used by shared loaded-client tests.
 */
final class MinecraftClientScreenAccess {
    private MinecraftClientScreenAccess() {
    }

    /**
     * Returns the active client screen.
     *
     * @param minecraft client GUI owner
     * @return active screen, or null
     */
    static Screen currentScreen(Minecraft minecraft) {
        return minecraft.gui.screen();
    }
}
