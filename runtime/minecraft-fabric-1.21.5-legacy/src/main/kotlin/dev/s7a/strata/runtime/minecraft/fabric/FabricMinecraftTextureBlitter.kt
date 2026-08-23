package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.RenderType

/**
 * Submits retained portable textures through the render-type factory API used by Minecraft 1.21.5.
 *
 * Calls borrow the supplied graphics context and resource location for the duration of one client-thread submission and retain neither value.
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
        graphics.blit(RenderType::guiTextured, location, x, y, 0f, 0f, width, height, width, height)
    }
}
