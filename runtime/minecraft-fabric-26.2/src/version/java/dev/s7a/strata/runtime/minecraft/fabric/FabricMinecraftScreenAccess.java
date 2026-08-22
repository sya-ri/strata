package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Isolates the 26.2 GUI holder API from the shared unobfuscated adapter.
 */
final class FabricMinecraftScreenAccess {
    private FabricMinecraftScreenAccess() {
    }

    /**
     * Returns the currently presented screen.
     *
     * @param minecraft client GUI owner
     * @return active screen, or null
     */
    static Screen currentScreen(Minecraft minecraft) {
        return minecraft.gui.screen();
    }

    /**
     * Replaces the currently presented screen.
     *
     * @param minecraft client GUI owner
     * @param screen replacement screen, or null
     */
    static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }
}
