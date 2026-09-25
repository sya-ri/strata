package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.minecraft.client.renderer.texture.AbstractTexture

/**
 * Owns one immutable portable-layer upload independently of its extracting screen.
 *
 * All access belongs to the render thread, and ownership transfers to a reserved GUI generation before native allocation starts.
 * The generation must seal initialization even when creation fails, and retain this owner through every GUI-consumption fence.
 * The direct borrowed texture view never owns storage; close attempts independent pixel and native cleanup without submitting new GPU work.
 * Physical destruction acknowledgement retains partial and deferred native resources without a global map or screen reference.
 */
@OptIn(InternalStrataRuntimeApi::class)
@Suppress("TooGenericExceptionCaught")
internal class FabricMinecraftPortableTexture private constructor() : NativeGuiResource {
    private var pixels: NativeImage? = null
    private var borrowed: AbstractTexture? = null
    private var storage: NativeGuiResource? = null
    private var nativeClosed = false
    private var closed = false

    /**
     * Borrows the initialized native texture and sampler on the render thread without transferring generation-owned storage.
     *
     * The view's close operation is inert; only this owner's fenced cleanup may release its native texture and view.
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
     * Initializes an already-retained owner with detached CPU pixels and a staged native upload.
     *
     * The caller must seal the owning generation in a finally path, because any thrown allocation or upload failure can leave partial GPU work.
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
                native.setPixel(x, y, image.argbAt(x, y))
            }
        }
        initializeFabricMinecraftPortableTexture(native, ::retainStorage)
    }

    /**
     * Initializes an already-retained owner from one immutable source image without a viewport-sized intermediate raster.
     *
     * The source is borrowed only for the synchronous CPU-to-native copy and is not retained by the texture.
     */
    @JvmSynthetic
    internal fun initialize(image: DrawImage) {
        RenderSystem.assertOnRenderThread()
        check(pixels == null && storage == null && closed.not()) { "A portable texture can initialize only once." }
        val native = NativeImage(image.size.width, image.size.height, false)
        pixels = native
        for (y in 0 until image.size.height) {
            for (x in 0 until image.size.width) {
                native.setPixel(x, y, image.argbAt(x, y))
            }
        }
        initializeFabricMinecraftPortableTexture(native, ::retainStorage)
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

    /**
     * Releases the CPU upload buffer after the device initialization fence completes while retaining immutable GPU storage.
     *
     * A failed close leaves the buffer owned for a later terminal retry.
     */
    @JvmSynthetic
    internal fun releaseUploadPixels() {
        RenderSystem.assertOnRenderThread()
        val retained = pixels ?: return
        retained.close()
        pixels = null
    }

    @JvmSynthetic
    override fun close() {
        RenderSystem.assertOnRenderThread()
        if (closed) return
        var failure: Throwable? = null
        try {
            if (nativeClosed.not()) {
                storage?.close()
                nativeClosed = true
            }
        } catch (caught: Throwable) {
            failure = caught
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
     * Creates immutable portable uploads under an existing GUI-generation lifetime reservation without retaining a cache.
     */
    internal companion object {
        /**
         * Transfers an empty owner before allocating CPU pixels, GPU storage, or native views.
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
            val owner = FabricMinecraftPortableTexture()
            retain(owner)
            owner.initialize(image)
            return owner
        }

        /**
         * Transfers an empty owner before directly copying and uploading one immutable source image.
         *
         * @param image immutable image borrowed only during synchronous initialization.
         * @param retain device cache receiver that takes exclusive resource ownership before allocation.
         * @return initialized texture whose cache key may remain the referential identity of [image].
         */
        @JvmSynthetic
        internal fun create(
            image: DrawImage,
            retain: (NativeGuiResource) -> Unit,
        ): FabricMinecraftPortableTexture {
            RenderSystem.assertOnRenderThread()
            val owner = FabricMinecraftPortableTexture()
            retain(owner)
            owner.initialize(image)
            return owner
        }
    }
}
