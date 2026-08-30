package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns one immutable portable-layer upload and its unique non-owning texture-manager registration.
 *
 * All access belongs to the render thread, and ownership transfers to a reserved GUI generation before native allocation starts.
 * The generation must seal initialization even when creation fails, and retain this owner through every GUI-consumption fence.
 * Close unregisters the borrowed view and attempts independent pixel and native cleanup; physical acknowledgement may be deferred.
 * Neither the registration nor this resource retains a screen, node, command list, or producer callback.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
internal class FabricMinecraftPortableTexture private constructor(
    @get:JvmSynthetic
    internal val location: MinecraftResourceLocation,
) : NativeGuiResource {
    private var pixels: NativeImage? = null
    private var borrowed: AbstractTexture? = null
    private var storage: NativeGuiResource? = null
    private var registrationAttempted = false
    private var unregistered = false
    private var nativeClosed = false
    private var closed = false

    /**
     * Borrows the initialized native texture on the render thread without transferring its generation-owned storage.
     *
     * The view's close and reload operations cannot release storage; only this owner's fenced cleanup can do so.
     * Access before successful initialization or after close fails explicitly.
     */
    @get:JvmSynthetic
    internal val texture: AbstractTexture
        get() {
            RenderSystem.assertOnRenderThread()
            check(closed.not()) { "A retired portable texture cannot be borrowed." }
            return checkNotNull(borrowed) { "The portable texture has not initialized its native view." }
        }

    /**
     * Initializes an already-retained owner with detached CPU pixels and then registers its unique borrowed native view.
     *
     * The caller must seal the owning generation in a finally path, because any thrown allocation, upload, or registration failure can leave partial GPU work.
     * This operation belongs to the render thread and may be invoked only once; no eager native rollback occurs.
     */
    @JvmSynthetic
    internal fun initialize(image: HeadlessImage) {
        RenderSystem.assertOnRenderThread()
        check(pixels == null && storage == null && closed.not()) { "A portable texture can initialize only once." }
        val native = NativeImage(image.size.width, image.size.height, false)
        pixels = native
        for (y in 0 until image.size.height) {
            for (x in 0 until image.size.width) {
                setFabricMinecraftArgbPixel(native, x, y, image.argbAt(x, y))
            }
        }
        initializeFabricMinecraftPortableTexture(native, ::retainStorage)
        registrationAttempted = true
        Minecraft.getInstance().textureManager.register(location, texture)
    }

    /**
     * Takes one empty staged native owner before it allocates or uploads storage.
     *
     * The version helper invokes this only on the render thread and then retains no independent ownership.
     * Repeated transfer is rejected before replacing the original owner.
     */
    @JvmSynthetic
    internal fun retainStorage(
        view: AbstractTexture,
        resource: NativeGuiResource,
    ) {
        check(storage == null) { "A portable texture already owns native storage." }
        borrowed = view
        storage = resource
    }

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        var failure: Throwable? = null
        try {
            if (registrationAttempted && unregistered.not()) {
                Minecraft.getInstance().textureManager.release(location)
                unregistered = true
            }
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            if (nativeClosed.not()) {
                storage?.close()
                nativeClosed = true
            }
        } catch (caught: Throwable) {
            val primary = failure
            if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
        }
        try {
            pixels?.close()
            pixels = null
        } catch (caught: Throwable) {
            val primary = failure
            if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
        }
        failure?.let { throw it }
        borrowed = null
        closed = true
    }

    @JvmSynthetic
    override fun isDestroyed(): Boolean {
        RenderSystem.assertOnRenderThread()
        check(closed) { "Portable texture destruction is queried only after successful close." }
        return storage?.isDestroyed() ?: true
    }

    /**
     * Creates immutable portable uploads under an existing GUI-generation lifetime reservation.
     *
     * This factory retains only a process-local identifier counter; every texture and pixel buffer belongs to its returned or partially initialized owner.
     */
    internal companion object {
        /**
         * Allocates distinct registration names without retaining textures or changing an existing generation's identity.
         */
        @JvmField
        @field:JvmSynthetic
        internal val sequence = AtomicLong()

        /**
         * Transfers an empty owner before allocating CPU pixels, GPU storage, or a texture-manager entry.
         *
         * @param image immutable complete layer image copied into owned native pixel storage.
         * @param retain reserved generation receiver, invoked once before allocation; the receiver must seal its generation even if this method throws.
         * @return an initialized immutable upload owned exclusively by the receiving generation.
         * @throws Throwable when ownership transfer or initialization fails; every resource allocated after transfer remains with that generation.
         */
        @JvmSynthetic
        internal fun create(
            image: HeadlessImage,
            retain: (NativeGuiResource) -> Unit,
        ): FabricMinecraftPortableTexture {
            RenderSystem.assertOnRenderThread()
            val location = minecraftResourceLocation("strata", "runtime/portable/${sequence.getAndIncrement().toULong()}")
            val owner = FabricMinecraftPortableTexture(location)
            retain(owner)
            owner.initialize(image)
            return owner
        }
    }
}
