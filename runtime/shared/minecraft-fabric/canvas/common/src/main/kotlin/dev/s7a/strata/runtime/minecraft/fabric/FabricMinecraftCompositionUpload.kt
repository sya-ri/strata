package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Owns both command-metadata staging images on the render thread until the enclosing generation's upload fence completes.
 * Each image transfers into this owner before copying; failed allocation, copying or native initialization preserves prior allocations.
 * The owner retains neither commands nor sources after synchronous initialization and retries only failed image closes.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftCompositionUpload {
    private var indices: NativeImage? = null
    private var factors: NativeImage? = null

    /**
     * Copies exact axis and Float-bit metadata before delegating to the adapter's already-fenced native owner.
     * The enclosing portable texture must retain this empty upload owner before calling this operation once.
     */
    @JvmSynthetic
    internal fun initialize(
        composition: FabricMinecraftCompositionMap,
        sources: List<AbstractTexture?>,
        retain: (AbstractTexture, NativeGuiResource) -> Unit,
    ) {
        val axes = composition.indices
        val nativeIndices = NativeImage(axes.size.width, axes.size.height, false)
        indices = nativeIndices
        uploadFabricMinecraftArgbPixels(nativeIndices, axes.size, axes::argbAt)
        val weights = composition.factors
        val nativeFactors = NativeImage(weights.size.width, weights.size.height, false)
        factors = nativeFactors
        uploadFabricMinecraftArgbPixels(nativeFactors, weights.size, weights::argbAt)
        initializeFabricMinecraftCompositionTexture(nativeIndices, nativeFactors, composition.physicalSize, sources, retain)
    }

    /**
     * Attempts both independent staging closes in allocation order; a failed close remains owned for terminal retry.
     */
    @JvmSynthetic
    internal fun close() {
        FabricMinecraftFailures.runWithCleanup(
            {
                indices?.close()
                indices = null
            },
            {
                factors?.close()
                factors = null
            },
        )
    }
}
