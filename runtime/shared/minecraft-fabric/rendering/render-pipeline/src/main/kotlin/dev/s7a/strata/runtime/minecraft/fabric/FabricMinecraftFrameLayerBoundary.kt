package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics

/**
 * Applies the selected native renderer's ordering boundary before the next retained frame layer.
 */
internal object FabricMinecraftFrameLayerBoundary {
    /**
     * Advances deferred extraction to its next native stratum; immediate rendering already preserves call order.
     * Native failures escape unchanged on the client thread.
     */
    fun advance(graphics: GuiGraphics) {
        graphics.nextStratum()
    }
}
