package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Owns staged device allocations while exposing an inert texture-manager view.
 * The generation fences initialization and GUI consumption before requesting destruction.
 * The compatible texture/view fields are cleared only after every independent native close succeeds.
 */
internal abstract class FabricMinecraftPortableTextureStorage : AbstractTexture() {
    /**
     * Records each allocation before another operation can fail and retains it through physical acknowledgement.
     */
    @get:JvmSynthetic
    protected val owned = FabricMinecraftNativeStorage()

    /**
     * Attempts every independent native close and clears borrowed fields only after all requests succeed.
     * Successful close steps are not repeated on retry; original objects remain available to destruction probes.
     */
    @JvmSynthetic
    internal fun destroy() {
        RenderSystem.assertOnRenderThread()
        owned.close()
        textureView = null
        texture = null
    }

    /**
     * Acknowledges original allocations without waiting after every close request has succeeded.
     * Incomplete acknowledgement retains the owning generation's permit.
     */
    @JvmSynthetic
    internal fun isDestroyed(): Boolean {
        RenderSystem.assertOnRenderThread()
        return owned.isDestroyed()
    }

    @JvmSynthetic
    override fun close() = Unit
}
