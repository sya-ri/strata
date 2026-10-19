package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.render.DrawImage

/**
 * Enables the existing bounded axis-index texture only when the active adapter can allocate its complete lookup.
 * The exact adapter supplies its RGBA texture bound, and the check runs on the render thread before reservation.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftExactSampling(): Boolean {
    RenderSystem.assertOnRenderThread()
    return 4_096 <= fabricMinecraftMaximumTextureSize()
}

/**
 * Checks whether one immutable image fits the active adapter's RGBA texture bound before direct-cache reservation.
 * The source is borrowed on the render thread; both dimensions must fit the same native limit.
 */
@JvmSynthetic
internal fun supportsFabricMinecraftSampledImage(image: DrawImage): Boolean {
    RenderSystem.assertOnRenderThread()
    val maximum = fabricMinecraftMaximumTextureSize()
    return image.size.width <= maximum && image.size.height <= maximum
}
