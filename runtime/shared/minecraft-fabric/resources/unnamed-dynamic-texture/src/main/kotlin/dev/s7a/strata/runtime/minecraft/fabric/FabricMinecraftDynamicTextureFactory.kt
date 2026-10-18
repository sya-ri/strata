package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture

/**
 * Transfers caller-owned [image] to an unnamed native dynamic texture on the render thread.
 * The caller must close [image] if native construction fails.
 */
internal fun createFabricMinecraftDynamicTexture(image: NativeImage): DynamicTexture = DynamicTexture(image)
