package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture

/**
 * Transfers ownership of [pixels] to a named dynamic texture using the Minecraft 1.21.5 constructor.
 *
 * The caller owns the returned texture and remains responsible for closing [pixels] if construction fails.
 * This function must run on the Minecraft client render thread and propagates native allocation failures.
 *
 * @param pixels Native pixels prepared for the retained frame layer.
 * @return A dynamic texture that owns [pixels].
 */
internal fun createFabricMinecraftDynamicTexture(pixels: NativeImage): DynamicTexture = DynamicTexture({ "Strata runtime frame layer" }, pixels)
