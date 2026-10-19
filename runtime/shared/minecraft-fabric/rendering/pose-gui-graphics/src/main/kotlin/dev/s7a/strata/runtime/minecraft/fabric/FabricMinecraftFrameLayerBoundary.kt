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
    @Suppress("UnusedParameter") // The immediate renderer needs no state change at this shared presentation boundary.
    fun advance(graphics: GuiGraphics) {
        // Immediate texture blits preserve submission order without advancing a deferred stratum.
    }
}
