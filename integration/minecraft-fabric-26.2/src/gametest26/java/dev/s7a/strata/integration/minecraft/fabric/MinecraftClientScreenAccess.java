package dev.s7a.strata.integration.minecraft.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Isolates the 26.2 GUI holder used by shared loaded-client tests.
 */
final class MinecraftClientScreenAccess {
    /**
     * Changes test-owned HUD visibility on the client thread, returning the state the caller must restore.
     * Native screens keep rendering while vanilla HUD elements and toasts are hidden.
     *
     * @param minecraft borrowed client GUI owner
     * @param hidden temporary HUD visibility
     * @return previous hidden state
     */
    static boolean exchangeHudHidden(Minecraft minecraft, boolean hidden) {
        boolean previous = minecraft.gui.hud.isHidden();
        if (previous != hidden) {
            minecraft.gui.hud.toggle();
        }
        return previous;
    }

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
