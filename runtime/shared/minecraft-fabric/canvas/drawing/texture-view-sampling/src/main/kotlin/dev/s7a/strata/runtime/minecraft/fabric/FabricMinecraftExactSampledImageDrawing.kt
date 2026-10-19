@file:JvmSynthetic

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntRect
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Queues materialized exact output through the native GUI path while the portable generation retains it.
 * The common signature also serves backends that sample [source] directly in their GUI shader.
 */
@JvmSynthetic
@Suppress("UNUSED_PARAMETER")
internal fun drawFabricMinecraftExactSampledImage(
    graphics: GuiGraphicsExtractor,
    source: AbstractTexture,
    prepared: AbstractTexture,
    bounds: IntRect,
) {
    graphics.blit(prepared.getTextureView(), prepared.getSampler(), bounds.left, bounds.top, bounds.right, bounds.bottom, 0f, 1f, 0f, 1f)
}
