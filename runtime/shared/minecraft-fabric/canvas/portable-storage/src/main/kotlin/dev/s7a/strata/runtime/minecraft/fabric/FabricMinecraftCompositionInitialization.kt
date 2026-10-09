package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Transfers an empty adapter owner before allocating its destinations and uploading exact command metadata.
 * Sources remain borrowed under the caller's initialization and GUI-consumption fences, including failed initialization.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftCompositionTexture(
    indices: NativeImage,
    factors: NativeImage,
    size: IntSize,
    sources: List<AbstractTexture?>,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
    workspace: FabricMinecraftCompositionWorkspace? = null,
) {
    retainFabricMinecraftPortableStorage(retain).native.initializeComposition(indices, factors, size, sources, workspace?.borrow(size))
}

/**
 * Transfers one empty intermediate owner before allocating its exact RGBA8 texture and optional view.
 * The receiving workspace belongs to the same fenced generation as every borrowing tile.
 */
@OptIn(InternalStrataRuntimeApi::class)
@JvmSynthetic
internal fun initializeFabricMinecraftCompositionScratch(
    size: IntSize,
    retain: (AbstractTexture, NativeGuiResource) -> Unit,
) {
    retainFabricMinecraftPortableStorage(retain).native.initializeCompositionScratch(size)
}
