package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.headless.HeadlessRasterScratch
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceOwnerId
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceSet
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResources
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Caches the current portable image generation and borrows its complete texture list during ordered presentation.
 *
 * Every call belongs to the native render thread. The key is the complete ordered list of localized commands, logical extents, and GUI scales;
 * unchanged prefix and suffix layers reuse their uploads across index shifts, while changed layers allocate immutable storage in a separately fenced generation.
 * A stable presenter identity admits at most three active or retired generations, within the device's separate 64-set portable budget.
 * Each set reserves its checked layer extents before native allocation; exact GPU sampling includes a conservative rectangle covering output and axis metadata.
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
     * @param images immutable raster or exact GPU descriptions in their combined display-list order.
     * @param rasterized render-thread counter callback invoked once before each rasterization attempt, never retained.
     * @param uploaded render-thread counter callback invoked after each successful upload, never retained.
     * @param sampled optional synchronous factory for exact GPU output; the caller pins and marks its source before recording the pass.
     * @param submit borrowed callback receiving every prepared texture and a marker it must invoke before each portable blit.
     * The callback must not retain either argument and may trigger reentrant screen release.
     * @throws Throwable when capacity, preparation, submission, or independently attempted cleanup fails.
     */
    @JvmSynthetic
    internal fun present(
        images: List<FabricMinecraftPortableImage>,
        rasterized: () -> Unit,
        uploaded: (FabricMinecraftPortableImage) -> Unit,
        sampled: ((FabricMinecraftSamplingMap, (NativeGuiResource) -> Unit) -> FabricMinecraftPortableTexture)? = null,
        submit: (List<FabricMinecraftPortableTexture>, () -> Unit) -> Unit,
    ) {
        if (images.isEmpty()) {
            retireCurrent()
            submit(emptyList()) {}
            return
        }
        val prepared = prepare(images, rasterized, uploaded, sampled)
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
        uploaded: (FabricMinecraftPortableImage) -> Unit,
        sampled: ((FabricMinecraftSamplingMap, (NativeGuiResource) -> Unit) -> FabricMinecraftPortableTexture)?,
    ): Prepared {
        val previous = current
        reuseCurrent(images)?.let { return it }
        val resources = NativeCanvasDevices.device(FabricNativeCanvasDriver).guiResources
        val identity = ownerId ?: resources.createOwnerId().also { ownerId = it }
        val matches = previous?.let { matchFabricMinecraftPortableImages(it.images, images) }
        val set = resources.reserve(identity, images.map { it.reservationSize })
        val textures = ArrayList<FabricMinecraftPortableTexture>(images.size)
        var failure: Throwable? = null
        try {
            val rasterPixels = allocateRasterPixels(images, matches)
            HeadlessRasterScratch().use { scratch ->
                images.forEachIndexed { index, input ->
                    val source = matchedSource(matches, index)
                    if (previous != null && 0 <= source) {
                        resources.reuse(set, previous.set, source)
                        textures.add(previous.textures[source])
                    } else {
                        textures.add(prepareTexture(input, rasterPixels, scratch, rasterized, sampled) { resource -> resources.add(set, resource) })
                        uploaded(input)
                    }
                }
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
        val prepared = Prepared(images, textures.toList(), resources, set)
        current = prepared
        previous?.let { it.resources.release(it.set) }
        return prepared
    }

    private fun matchedSource(
        matches: IntArray?,
        index: Int,
    ): Int = matches?.get(index) ?: -1

    private fun allocateRasterPixels(
        images: List<FabricMinecraftPortableImage>,
        matches: IntArray?,
    ): IntArray? {
        var area = 0
        images.forEachIndexed { index, input ->
            if (input.sampling == null && matchedSource(matches, index) < 0) {
                area = maxOf(area, Math.multiplyExact(input.physicalSize.width, input.physicalSize.height))
            }
        }
        return if (area == 0) null else IntArray(area)
    }

    private fun prepareTexture(
        input: FabricMinecraftPortableImage,
        pixels: IntArray?,
        scratch: HeadlessRasterScratch?,
        rasterized: () -> Unit,
        sampled: ((FabricMinecraftSamplingMap, (NativeGuiResource) -> Unit) -> FabricMinecraftPortableTexture)?,
        retain: (NativeGuiResource) -> Unit,
    ): FabricMinecraftPortableTexture {
        val sampling = input.sampling
        if (sampling != null) return checkNotNull(sampled) { "GPU sampling requires a pinned source factory." }(sampling, retain)
        rasterized()
        return FabricMinecraftPortableTexture.create(input, checkNotNull(pixels), scratch, retain)
    }

    private fun reuseCurrent(images: List<FabricMinecraftPortableImage>): Prepared? {
        val previous = current ?: return null
        if (previous.images === images) return previous
        if (equivalent(previous.images, images).not()) return null
        // Equal pixels must not retain obsolete source-image storage through a previous command description.
        return Prepared(images, previous.textures, previous.resources, previous.set).also { current = it }
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
