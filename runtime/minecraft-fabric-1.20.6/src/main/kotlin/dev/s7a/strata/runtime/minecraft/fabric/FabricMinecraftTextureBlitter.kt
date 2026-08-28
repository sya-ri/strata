package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics

/**
 * Submits retained portable textures through Minecraft 1.20.6's resource-location blit API.
 */
internal object FabricMinecraftTextureBlitter {
    /**
     * Maps a texture rasterized at physical GUI density onto its logical destination without retaining native objects.
     *
     * @param graphics active client-thread graphics context borrowed for this call.
     * @param location registered texture location borrowed for this call.
     * @param x destination left edge in GUI pixels.
     * @param y destination top edge in GUI pixels.
     * @param width destination width in logical GUI pixels.
     * @param height destination height in logical GUI pixels.
     * @param textureWidth complete source width in physical pixels.
     * @param textureHeight complete source height in physical pixels.
     */
    internal fun blit(
        graphics: GuiGraphics,
        location: MinecraftResourceLocation,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        textureWidth: Int,
        textureHeight: Int,
    ) {
        graphics.blit(location, x, y, width, height, 0f, 0f, textureWidth, textureHeight, textureWidth, textureHeight)
    }
}
