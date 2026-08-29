package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderPipelines

/**
 * Submits retained portable and native textures through the render-pipeline API used by Minecraft 1.21.6 and later supported remapped releases.
 *
 * Calls borrow the supplied graphics context and resource location for the duration of one client-thread submission and retain neither value.
 */
internal object FabricMinecraftTextureBlitter {
    /**
     * Maps a complete texture rasterized at physical GUI density onto its logical destination in display-list order without retaining native objects.
     *
     * The caller keeps the texture alive until actual GUI consumption.
     *
     * @param graphics active client-thread graphics context borrowed for this call.
     * @param location registered texture location borrowed for this call.
     * @param x destination left edge in GUI pixels.
     * @param y destination top edge in GUI pixels.
     * @param width destination width in logical GUI pixels.
     * @param height destination height in logical GUI pixels.
     * @param textureWidth complete source width in physical pixels.
     * @param textureHeight complete source height in physical pixels.
     * @throws Throwable when native flushing or texture submission fails.
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
        graphics.blit(RenderPipelines.GUI_TEXTURED, location, x, y, 0f, 0f, width, height, textureWidth, textureHeight, textureWidth, textureHeight)
    }

    /**
     * Draws one complete native texture whose nominal source extent equals its logical destination.
     *
     * Equal nominal source and texture extents select full source UVs even when the actual native image has a different physical resolution.
     * Ownership, ordering, threading, and failure behavior follow the physical-extent overload.
     */
    internal fun blit(
        graphics: GuiGraphics,
        location: MinecraftResourceLocation,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        blit(graphics, location, x, y, width, height, width, height)
    }
}
