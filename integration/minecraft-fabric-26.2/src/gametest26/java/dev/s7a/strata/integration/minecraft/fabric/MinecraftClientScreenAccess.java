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

    /**
     * Reports whether a native GUI overlay remains active, without changing client state.
     *
     * @param minecraft borrowed client GUI owner, accessed on its client thread
     * @return whether the client currently has an overlay
     */
    static boolean hasOverlay(Minecraft minecraft) {
        return minecraft.gui.overlay() != null;
    }

    /**
     * Replaces the active client screen.
     *
     * @param minecraft client GUI owner
     * @param screen replacement screen, or null
     */
    static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.gui.setScreen(screen);
    }
}
