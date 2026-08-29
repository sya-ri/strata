package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.render.DrawCommand
import net.minecraft.client.gui.GuiGraphics

/**
 * Draws one eligible sampled image through the legacy GUI pose and straight-alpha texture pipeline.
 *
 * The integer unit blit is transformed to the original floating destination, preserving source texel UVs without rasterizing a viewport layer.
 * The borrowed pose and texture are restored or released by their owners after this synchronous call.
 *
 * @param graphics active client-thread GUI target whose pose is restored before return.
 * @param texture immutable cached texture borrowed through this synchronous submission.
 * @param command validated direct sampled-image command.
 * @throws Throwable when native pose, blending, or texture submission fails; pose cleanup is attempted independently.
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
    pose.pushPose()
    FabricMinecraftFailures.runWithCleanup(
        {
            pose.translate(destination.left.toDouble(), destination.top.toDouble(), 0.0)
            pose.scale(destination.width, destination.height, 1f)
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
        pose::popPose,
    )
}
