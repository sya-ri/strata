package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Owns portable-image or command-metadata staging images until the enclosing generation's upload fence completes.
 * Each image transfers into this owner before copying; failed allocation, copying or native initialization preserves prior allocations.
 * The owner retains neither commands nor sources after synchronous initialization and retries only failed image closes.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftUploadPixels {
    private var indices: NativeImage? = null
    private var factors: NativeImage? = null

    /**
     * Indicates whether this owner has acquired any staging storage for its single initialization.
     */
    @get:JvmSynthetic
    internal val isEmpty: Boolean
        get() = indices == null && factors == null

    /**
     * Transfers primary staging storage before copying immutable ARGB pixels on the render thread.
     * The source callback is borrowed only for this synchronous copy; failures retain the allocated image for fenced cleanup.
     */
    @JvmSynthetic
    internal fun stage(
        size: IntSize,
        pixel: (Int, Int) -> Int,
    ): NativeImage {
        check(isEmpty) { "A portable upload can initialize only once." }
        val native = NativeImage(size.width, size.height, false)
        indices = native
        uploadFabricMinecraftArgbPixels(native, size, pixel)
        return native
    }

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
        val nativeIndices = stage(axes.size, axes::argbAt)
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
