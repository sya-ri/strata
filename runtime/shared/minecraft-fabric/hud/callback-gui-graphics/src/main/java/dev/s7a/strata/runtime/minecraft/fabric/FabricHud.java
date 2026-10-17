package dev.s7a.strata.runtime.minecraft.fabric;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import kotlin.Unit;

/** Registers Strata overlays through the target's official Fabric HUD API. */
public final class FabricHud {
    private FabricHud() { }

    /** Registers one process-lifetime HUD element using Fabric's layer and visibility contract. */
    public static void initialize() {
        HudRenderCallback.EVENT.register((graphics, delta) -> {
            FabricUiSessions.INSTANCE.renderHuds((screen, mouseX, mouseY) -> {
                screen.render(graphics, mouseX, mouseY, 0.0f);
                return Unit.INSTANCE;
            });
        });
    }
}
