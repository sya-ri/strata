package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics

/**
 * Submits retained portable textures through Minecraft 1.20.5's resource-location blit API.
 */
internal object FabricMinecraftTextureBlitter {
    /**
     * Draws one complete retained texture at its destination without scaling or retaining native objects.
     *
     * @param graphics active client-thread graphics context borrowed for this call.
     * @param location registered texture location borrowed for this call.
     * @param x destination left edge in GUI pixels.
     * @param y destination top edge in GUI pixels.
     * @param width texture and destination width in pixels.
     * @param height texture and destination height in pixels.
     */
    internal fun blit(
        graphics: GuiGraphics,
        location: MinecraftResourceLocation,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        graphics.blit(location, x, y, 0, 0f, 0f, width, height, width, height)
    }
}
