package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.render.DrawCommand
import net.minecraft.client.gui.GuiGraphics

/**
 * Draws one eligible sampled image through the matrix-stack GUI texture pipeline used by Minecraft 1.21.6 and later remapped releases.
 *
 * The integer unit blit is transformed to the original floating destination, preserving source texel UVs without rasterizing a viewport layer.
 *
 * @param graphics active client-thread GUI target whose matrix is restored before return.
 * @param texture immutable cached texture borrowed through this synchronous submission.
 * @param command validated direct sampled-image command.
 * @throws Throwable when native matrix or texture submission fails; matrix cleanup is attempted independently.
 */
@JvmSynthetic
internal fun drawFabricMinecraftSampledImage(
    graphics: GuiGraphics,
    texture: FabricMinecraftPortableTexture,
    command: DrawCommand.SampledImage,
) {
    val pose = graphics.pose()
    val destination = command.destination
    val source = command.source
    pose.pushMatrix()
    FabricMinecraftFailures.runWithCleanup(
        {
            pose.translate(destination.left, destination.top)
            pose.scale(destination.width, destination.height)
            FabricMinecraftTextureBlitter.blitSampled(
                graphics,
                texture.location,
                source.left.toInt(),
                source.top.toInt(),
                source.width.toInt(),
                source.height.toInt(),
                command.image.size.width,
                command.image.size.height,
            )
        },
        pose::popMatrix,
    )
}
