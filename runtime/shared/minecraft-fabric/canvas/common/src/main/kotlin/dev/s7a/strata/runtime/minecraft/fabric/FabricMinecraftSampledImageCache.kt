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
     * @param inputs immutable CPU frame inputs whose requested source identities are pinned in display-list order.
     * @param submit borrowed callback receiving the current resolved inputs and texture lookup; it must mark an image queued immediately before each direct GUI draw.
     * @throws Throwable when upload, native submission, or cleanup fails.
     */
    @JvmSynthetic
    internal fun present(
        inputs: FabricMinecraftFrameInputs,
        submit: (resolved: FabricMinecraftFrameInputs, texture: (DrawImage) -> FabricMinecraftPortableTexture?, queued: (DrawImage) -> Unit) -> Unit,
    ) {
        if (released) {
            owner = device.openOwner()
            released = false
        }
        device.borrow(owner, inputs.sampled).use { borrowed ->
            val resolved = inputs.resolve { borrowed.texture(it) != null }
            submit(resolved, borrowed::texture, borrowed::queued)
        }
    }

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
