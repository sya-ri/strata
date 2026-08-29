package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics

/**
 * Submits retained portable and native textures through Minecraft 1.20.6's resource-location blit API.
 */
internal object FabricMinecraftTextureBlitter {
    /**
     * Draws an integer source-texel rectangle into the caller's transformed unit destination.
     *
     * The graphics context and registered texture identifier are borrowed only for this render-thread submission.
     *
     * @param graphics active client-thread graphics context.
     * @param location registered immutable texture identifier.
     * @param sourceX source texel left edge.
     * @param sourceY source texel top edge.
     * @param sourceWidth positive source texel width.
     * @param sourceHeight positive source texel height.
     * @param textureWidth complete native texture width.
     * @param textureHeight complete native texture height.
     * @throws Throwable when native state setup or texture submission fails.
     */
    internal fun blitSampled(
        graphics: GuiGraphics,
        location: MinecraftResourceLocation,
        sourceX: Int,
        sourceY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        textureWidth: Int,
        textureHeight: Int,
    ) {
        withFabricMinecraftGuiBlending(graphics) {
            graphics.blit(location, 0, 0, 1, 1, sourceX.toFloat(), sourceY.toFloat(), sourceWidth, sourceHeight, textureWidth, textureHeight)
        }
    }

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
        withFabricMinecraftGuiBlending(graphics) {
            graphics.blit(location, x, y, width, height, 0f, 0f, textureWidth, textureHeight, textureWidth, textureHeight)
        }
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
