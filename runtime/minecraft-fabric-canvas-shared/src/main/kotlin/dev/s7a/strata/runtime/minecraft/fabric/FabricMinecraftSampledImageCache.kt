package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns one screen's references into the device sampled-image cache.
 *
 * The screen retains source identities only while attached; release retires unshared native entries without waiting, while the registered device manager preserves pending GPU uses.
 * Every call belongs to the Minecraft render thread.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class FabricMinecraftSampledImageCache {
    private val device = FabricMinecraftSampledImageDevices.device(FabricNativeCanvasDriver)
    private var owner = device.openOwner()
    private var released = false

    /**
     * Pins requested source identities and borrows available textures through one complete ordered submission.
     *
     * @param images source identities in display-list order; placement and GUI scale are deliberately absent.
     * @param hit callback invoked for each requested identity that already has active device storage.
     * @param miss callback invoked for each requested identity without active device storage.
     * @param uploaded callback invoked after each successful native upload.
     * @param evicted callback invoked after each local least-recently-used owner eviction.
     * @param submit borrowed callback that must mark an image queued immediately before each direct GUI draw.
     * @throws Throwable when upload, native submission, or cleanup fails.
     */
    @Suppress("LongParameterList")
    @JvmSynthetic
    internal fun present(
        images: List<DrawImage>,
        hit: () -> Unit,
        miss: () -> Unit,
        uploaded: () -> Unit,
        evicted: () -> Unit,
        submit: (texture: (DrawImage) -> FabricMinecraftPortableTexture?, queued: (DrawImage) -> Unit) -> Unit,
    ) {
        if (released) {
            owner = device.openOwner()
            released = false
        }
        device.borrow(owner, images, hit, miss, uploaded, evicted).use { borrowed ->
            submit(borrowed::texture, borrowed::queued)
        }
    }

    /**
     * Reports device-budgeted active and retired identities without polling native work.
     */
    @JvmSynthetic
    internal fun retainedEntryCount(): Int = device.retainedResourceCount()

    /**
     * Reports device-budgeted active and retired RGBA bytes without polling native work.
     */
    @JvmSynthetic
    internal fun retainedByteCount(): Long = device.retainedResourceBytes()

    /**
     * Checks the active native texture extent without reserving cache state.
     */
    @JvmSynthetic
    internal fun supports(image: DrawImage): Boolean = supportsFabricMinecraftSampledImage(image)

    /**
     * Drops screen-owned source identities and requests device retirement without blocking.
     *
     * The cache may be reused after a later screen attachment.
     */
    @JvmSynthetic
    internal fun release() {
        if (released) return
        released = true
        device.release(owner)
    }
}
