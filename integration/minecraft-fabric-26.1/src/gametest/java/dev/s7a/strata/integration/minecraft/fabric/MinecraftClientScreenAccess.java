package dev.s7a.strata.integration.minecraft.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Isolates the 26.1 screen field used by shared loaded-client tests.
 */
final class MinecraftClientScreenAccess {
    /**
     * Changes test-owned HUD visibility on the client thread, returning the state the caller must restore.
     * Native screens keep rendering while vanilla HUD elements and toasts are hidden.
     *
     * @param minecraft borrowed client owner
     * @param hidden temporary HUD visibility
     * @return previous hidden state
     */
    static boolean exchangeHudHidden(Minecraft minecraft, boolean hidden) {
        boolean previous = minecraft.options.hideGui;
        minecraft.options.hideGui = hidden;
        return previous;
    }

    private MinecraftClientScreenAccess() {
    }

    /**
     * Returns the active client screen.
     *
     * @param minecraft client screen owner
     * @return active screen, or null
     */
    static Screen currentScreen(Minecraft minecraft) {
        return minecraft.screen;
    }

    /**
     * Reports whether a native GUI overlay remains active, without changing client state.
     *
     * @param minecraft borrowed client GUI owner, accessed on its client thread
     * @return whether the client currently has an overlay
     */
    static boolean hasOverlay(Minecraft minecraft) {
        return minecraft.getOverlay() != null;
    }

    /**
     * Replaces the active client screen.
     *
     * @param minecraft client screen owner
     * @param screen replacement screen, or null
     */
    static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }
}
