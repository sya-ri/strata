package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceOwnerId
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceSet
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResources
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Caches the current portable image generation and borrows its complete texture list during ordered presentation.
 *
 * Every call belongs to the native render thread. The key is the complete ordered list of localized commands, logical extents, and GUI scales;
 * identical inputs reuse all uploads, while changed inputs create a separately fenced generation without in-place writes.
 * A stable presenter identity admits at most three active or retired generations, within the device's separate 64-set portable budget.
 * Each set contains exactly its prepared command list's checked physical portable-layer extents, reserved before native allocation.
 * Release immediately drops screen-owned CPU and texture references; the independent device owns pending, retired, and physically releasing resources.
 * A full-presentation pin prevents reentrant screen cleanup or an intermediate GUI flush from destroying later layers.
 *
 * Exhausted permits fail before the first GUI command rather than associating stale portable pixels with new commands.
 * Native allocation, rasterization, submission, and cleanup failures preserve their original primary exception.
 */
@OptIn(InternalStrataRuntimeApi::class)
// Native allocation and borrowed GUI callbacks may throw any Throwable; independent release must preserve the first failure.
@Suppress("TooGenericExceptionCaught")
internal class FabricMinecraftPortableFrames {
    private var ownerId: NativeGuiResourceOwnerId? = null
    private var current: Prepared? = null

    /**
     * Release identity used to prevent an interrupted presenter from repopulating a detached screen's cache.
     *
     * Reads and changes belong to the render thread; this scalar retains no native resource.
     */
    @get:JvmSynthetic
    internal var releaseGeneration: Long = 0L
        private set

    /**
     * Prepares all portable images before invoking the borrowed ordered GUI submission.
     *
     * @param images immutable local image descriptions in portable-layer order.
     * @param rasterized render-thread counter callback invoked once before each rasterization attempt, never retained.
     * @param uploaded render-thread counter callback invoked after each successful upload, never retained.
     * @param submit borrowed callback receiving every prepared texture and a marker it must invoke before each portable blit.
     * The callback must not retain either argument and may trigger reentrant screen release.
     * @throws Throwable when capacity, preparation, submission, or independently attempted cleanup fails.
     */
    @JvmSynthetic
    internal fun present(
        images: List<FabricMinecraftPortableImage>,
        rasterized: () -> Unit,
        uploaded: () -> Unit,
        submit: (List<FabricMinecraftPortableTexture>, () -> Unit) -> Unit,
    ) {
        if (images.isEmpty()) {
            retireCurrent()
            submit(emptyList()) {}
            return
        }
        val prepared = prepare(images, rasterized, uploaded)
        val resources = prepared.resources
        resources.beginUse(prepared.set)
        FabricMinecraftFailures.runWithCleanup(
            { submit(prepared.textures) { resources.queued(prepared.set) } },
            { resources.endUse(prepared.set) },
        )
    }

    /**
     * Severs the screen's current drawing and texture references before requesting device-owned retirement.
     *
     * This owner-thread operation never waits and requires no free lifetime slot.
     * It is reusable after transient removal; the stable presenter identity preserves its bound across reattachment.
     * Cleanup failures propagate after references and the release identity have already changed.
     */
    @JvmSynthetic
    internal fun release() {
        releaseGeneration += 1L
        retireCurrent()
    }

    private fun prepare(
        images: List<FabricMinecraftPortableImage>,
        rasterized: () -> Unit,
        uploaded: () -> Unit,
    ): Prepared {
        val previous = current
        if (previous != null && equivalent(previous.images, images)) {
            // Equal pixels must not retain obsolete source-image storage through a previous command description.
            return Prepared(images.toList(), previous.textures, previous.resources, previous.set).also { current = it }
        }
        val resources = NativeCanvasDevices.device(FabricNativeCanvasDriver).guiResources
        val identity = ownerId ?: resources.createOwnerId().also { ownerId = it }
        val set = resources.reserve(identity, images.map { it.physicalSize })
        val textures = ArrayList<FabricMinecraftPortableTexture>(images.size)
        var failure: Throwable? = null
        try {
            images.forEach { input ->
                rasterized()
                val pixels = rasterizeHeadless(input.commands, input.size, input.scale)
                textures.add(FabricMinecraftPortableTexture.create(pixels) { resource -> resources.add(set, resource) })
                uploaded()
            }
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            resources.seal(set)
        } catch (caught: Throwable) {
            val primary = failure
            if (primary == null) failure = caught else FabricMinecraftFailures.addSuppressed(primary, caught)
        }
        val primary = failure
        if (primary != null) {
            try {
                resources.release(set)
            } catch (cleanup: Throwable) {
                FabricMinecraftFailures.addSuppressed(primary, cleanup)
            }
            throw primary
        }
        val prepared = Prepared(images.toList(), textures.toList(), resources, set)
        current = prepared
        previous?.let { it.resources.release(it.set) }
        return prepared
    }

    private fun retireCurrent() {
        val previous = current
        current = null
        previous?.let { it.resources.release(it.set) }
    }

    private fun equivalent(
        previous: List<FabricMinecraftPortableImage>,
        next: List<FabricMinecraftPortableImage>,
    ): Boolean = previous.size == next.size && previous.indices.all { previous[it].equivalent(next[it]) }

    private class Prepared(
        val images: List<FabricMinecraftPortableImage>,
        val textures: List<FabricMinecraftPortableTexture>,
        val resources: NativeGuiResources,
        val set: NativeGuiResourceSet,
    )
}
