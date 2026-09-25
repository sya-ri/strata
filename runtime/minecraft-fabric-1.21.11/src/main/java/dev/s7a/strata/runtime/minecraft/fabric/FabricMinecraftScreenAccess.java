package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * Isolates the 1.21.11 screen field API from the shared unobfuscated adapter.
 */
final class FabricMinecraftScreenAccess {
    /** Initializes a HUD wrapper at the current logical viewport. */
    static void initialize(Minecraft client, Screen screen) {
        screen.init(client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
    }

    /** Reads the native F1 visibility flag for this game version. */
    static boolean hudHidden(Minecraft client) {
        return client.options.hideGui;
    }

    /** Prevents forwarding into gameplay under loading overlays. */
    static boolean hasOverlay(Minecraft client) {
        return client.getOverlay() != null;
    }

    private FabricMinecraftScreenAccess() {
    }

    /**
     * Returns the currently presented screen.
     *
     * @param minecraft client screen owner
     * @return active screen, or null
     */
    static Screen currentScreen(Minecraft minecraft) {
        return minecraft.screen;
    }

    /**
     * Replaces the currently presented screen.
     *
     * @param minecraft client screen owner
     * @param screen replacement screen, or null
     */
    static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }
}
