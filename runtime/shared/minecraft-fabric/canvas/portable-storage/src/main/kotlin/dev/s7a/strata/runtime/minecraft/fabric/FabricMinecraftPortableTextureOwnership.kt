package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Transfers empty native storage before allocating or uploading one immutable portable image.
 * The caller owns [pixels], seals initialization even on failure, and retains every partial allocation through its GUI fences.
 * [retain] receives the borrowed texture-manager view and its sole native owner on the render thread before allocation starts.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftPortableTexture(
    pixels: NativeImage,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
) {
    RenderSystem.assertOnRenderThread()
    retainFabricMinecraftPortableStorage(retain).native.initialize(pixels)
}

/**
 * Transfers one empty adapter owner before the caller allocates native storage or records GPU work.
 * A rejected transfer propagates unchanged without allocation; initialization failures remain owned by the receiving generation.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun retainFabricMinecraftPortableStorage(retain: (AbstractTexture, NativeGuiResource) -> Unit): FabricMinecraftPortableNativeStorage {
    val storage = FabricMinecraftPortableNativeStorage()
    retain(storage.texture, storage)
    return storage
}
