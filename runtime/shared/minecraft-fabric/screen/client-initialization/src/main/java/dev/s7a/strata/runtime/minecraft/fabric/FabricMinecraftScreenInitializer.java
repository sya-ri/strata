package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Initializes a borrowed native screen at the current logical viewport on the client thread. */
final class FabricMinecraftScreenInitializer {
    private FabricMinecraftScreenInitializer() {}

    /** Supplies the exact native init signature required by this compiled adapter. */
    static void initialize(Minecraft client, Screen screen) {
        screen.init(client, client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());
    }
}
