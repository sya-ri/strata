package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTexture
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.textures.TextureFormat
import dev.s7a.strata.geometry.IntSize
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Allocates exact RGBA8 composition targets for the compatible texture-view and sampler adapters on the render thread.
 * Every texture and view transfers to [owned] before the next allocation; no upload or draw occurs before all pairs exist.
 * Sampler selection and native submission remain with the caller's adapter and GUI generation.
 */
@JvmSynthetic
internal fun allocateFabricMinecraftCompositionTargets(
    owned: FabricMinecraftNativeStorage,
    size: IntSize,
    indices: NativeImage,
    factors: NativeImage,
    scratch: AbstractTexture? = null,
    passCount: Int = 0,
): FabricMinecraftCompositionTargets<GpuTexture, GpuTextureView> {
    val device = RenderSystem.getDevice()
    return FabricMinecraftCompositionTargets.create(
        owned,
        size,
        IntSize(indices.width, indices.height),
        IntSize(factors.width, factors.height),
        { extent -> device.createTexture({ "Strata ordered composition destination" }, GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_COPY_DST, TextureFormat.RGBA8, extent.width, extent.height, 1, 1) },
        { label, extent -> device.createTexture({ label }, GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_TEXTURE_BINDING, TextureFormat.RGBA8, extent.width, extent.height, 1, 1) },
        device::createTextureView,
        scratch?.let { it.getTexture() to it.getTextureView() },
        passCount,
    )
}
