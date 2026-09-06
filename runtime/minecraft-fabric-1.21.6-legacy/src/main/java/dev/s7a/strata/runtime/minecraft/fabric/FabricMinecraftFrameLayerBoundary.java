package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Bridges ordered frame-layer submission to Minecraft's deferred GUI renderer.
 */
final class FabricMinecraftFrameLayerBoundary {
    private FabricMinecraftFrameLayerBoundary() {}

    /**
     * Advances to a new native stratum on the client thread before the next ordered frame layer is extracted.
     * A failure from {@link GuiGraphics#nextStratum()} is propagated unchanged.
     *
     * @param graphics borrowed native GUI target.
     */
    static void advance(GuiGraphics graphics) {
        graphics.nextStratum();
    }
}
