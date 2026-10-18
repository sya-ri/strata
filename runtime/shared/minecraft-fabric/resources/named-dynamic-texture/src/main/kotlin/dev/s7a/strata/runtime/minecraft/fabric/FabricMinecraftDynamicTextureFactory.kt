package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture

/**
 * Transfers caller-owned [pixels] to a named native dynamic texture on the render thread.
 * The caller must close [pixels] if native construction fails.
 */
internal fun createFabricMinecraftDynamicTexture(pixels: NativeImage): DynamicTexture = DynamicTexture({ "Strata runtime frame layer" }, pixels)
