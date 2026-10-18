package dev.s7a.strata.runtime.minecraft.fabric;

import net.minecraft.client.gui.GuiGraphics;

/**
 * Bridges ordered frame-layer submission to Minecraft versions whose GUI texture calls render immediately.
 */
final class FabricMinecraftFrameLayerBoundary {
    private FabricMinecraftFrameLayerBoundary() {}

    /**
     * Keeps the immediate renderer in its current ordering scope without a native call or failure.
     *
     * @param graphics borrowed native GUI target.
     */
    static void advance(GuiGraphics graphics) {
        // Texture blits through Minecraft 1.21.5 flush immediately, so their call order is already preserved.
    }
}
