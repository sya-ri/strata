package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.runtime.render.DrawCommand
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Queues the materialized exact output using the native GUI path and the existing portable generation lifetime.
 * The shared submission signature also serves adapters that read [source] directly in the GUI shader.
 */
@JvmSynthetic
@Suppress("UNUSED_PARAMETER")
internal fun drawFabricMinecraftExactSampledImage(
    graphics: GuiGraphicsExtractor,
    source: AbstractTexture,
    prepared: AbstractTexture,
    bounds: IntRect,
) {
    submitFabricMinecraftGuiCorners(bounds) { x0, y0, x1, y1 ->
        graphics.blit(prepared.getTextureView(), prepared.getSampler(), x0, y0, x1, y1, 0f, 1f, 0f, 1f)
    }
}

/**
 * Extracts one cached sampled image through the modern matrix-stack GUI texture path.
 *
 * The unit quad is transformed to the original floating destination while normalized UVs retain the floating source-texel rectangle.
 *
 * @param graphics active client-thread GUI extractor whose matrix is restored before return.
 * @param texture immutable cached texture borrowed through this synchronous extraction.
 * @param command validated direct sampled-image command.
 * @throws Throwable when native matrix or texture extraction fails; matrix cleanup is attempted independently.
 */
@JvmSynthetic
internal fun drawFabricMinecraftSampledImage(
    graphics: GuiGraphicsExtractor,
    texture: FabricMinecraftPortableTexture,
    command: DrawCommand.SampledImage,
) {
    val native = texture.texture
    val pose = graphics.pose()
    val destination = command.destination
    val source = command.source
    val width =
        command.image.size.width
            .toFloat()
    val height =
        command.image.size.height
            .toFloat()
    pose.pushMatrix()
    FabricMinecraftFailures.runWithCleanup(
        {
            pose.translate(destination.left, destination.top)
            pose.scale(destination.width, destination.height)
            graphics.blit(
                native.getTextureView(),
                native.getSampler(),
                0,
                0,
                1,
                1,
                source.left / width,
                source.right / width,
                source.top / height,
                source.bottom / height,
            )
        },
        pose::popMatrix,
    )
}
