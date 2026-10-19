package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Separates a borrowed texture-manager view from its staged, generation-owned native allocations.
 * The render-thread caller transfers this empty owner before initialization and fences all GPU work before close.
 * Allocation, destruction requests, and physical acknowledgement stay in the exact native adapter.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftPortableNativeStorage : NativeGuiResource {
    /**
     * Empty adapter storage; construction allocates no native resource.
     */
    @get:JvmSynthetic
    internal val native = FabricMinecraftPortableNativeTexture()

    /**
     * Non-owning registration view whose close never releases generation-owned storage.
     */
    @get:JvmSynthetic
    internal val texture: AbstractTexture
        get() = native

    @JvmSynthetic
    override fun close() {
        native.destroy()
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean = native.isDestroyed()
}
