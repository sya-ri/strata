package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceManager
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Owns immutable sampled-image textures and their fences for one physical Minecraft device.
 *
 * Source images are keyed by referential identity inside this device generation; placement, clip, GUI scale, and frame identity never participate.
 * Each screen owner retains at most 256 images and 64 MiB, while active, retired, and asynchronously destroying entries share a separate 512-image and 128 MiB device budget.
 * Capacity exhaustion leaves a command unavailable for the caller's portable fallback before allocating or partially drawing it.
 * Every operation and callback belongs to the constructing render thread, and terminal destruction is driven by [NativeGuiResourceManager].
 *
 * @param driver stable device driver borrowed for ordered fences only; native texture allocation remains in the version-owned texture factory.
 * @param supportsImage render-thread native extent preflight borrowed without retention.
 * @param createTexture ownership-transferring native texture factory; a null borrowed view is reserved for deterministic lifetime fixtures.
 */
@OptIn(InternalStrataRuntimeApi::class)
// Native allocation and cleanup callbacks may fail independently and must preserve the first failure.
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList", "TooGenericExceptionCaught")
internal class FabricMinecraftSampledImageDevice(
    private val driver: NativeCanvasDriver,
    private val supportsImage: (DrawImage) -> Boolean = ::supportsFabricMinecraftSampledImage,
    private val createTexture: (DrawImage, (NativeGuiResource) -> Unit) -> FabricMinecraftPortableTexture? =
        { image, retain -> FabricMinecraftPortableTexture.create(image, retain) },
) : NativeGuiResourceManager {
    private val ownerThread = Thread.currentThread()
    private val activeByImage = IdentityHashMap<DrawImage, Entry>()
    private val entries = LinkedHashSet<Entry>()
    private val owners = LinkedHashSet<Owner>()
    private var retainedBytes = 0L
    private var nextEntry = 0L
    private var nextUse = 0L
    private var terminal = false
    private var operating = false

    /**
     * Creates one reusable screen cache owner without allocating image or native storage.
     *
     * @return owner confined to this device and render thread.
     * @throws IllegalStateException off the owner thread, during callback reentry, or after terminal shutdown begins.
     */
    @JvmSynthetic
    internal fun openOwner(): Owner =
        operation {
            check(terminal.not()) { "Sampled-image owners cannot open after native shutdown begins." }
            Owner().also(owners::add)
        }

    /**
     * Pins all currently available requested images through one borrowed ordered GUI submission.
     *
     * Missing entries are uploaded at most once by source identity. Entries outside either cache budget remain absent so the caller can use its tight portable fallback.
     * The input list is borrowed and neither it nor unavailable images are retained.
     *
     * @param owner live screen owner returned by [openOwner].
     * @param images requested source images in deterministic display-list order.
     * @param uploaded callback invoked after each successful new upload, never retained.
     * @return borrowed identity lookup whose [Borrow.close] must run after submission.
     * @throws Throwable when allocation, upload, fencing, or independently attempted cleanup fails.
     */
    @JvmSynthetic
    internal fun borrow(
        owner: Owner,
        images: List<DrawImage>,
        hit: () -> Unit,
        miss: () -> Unit,
        uploaded: () -> Unit,
        evicted: () -> Unit,
    ): Borrow =
        operation {
            requireOwner(owner)
            check(owner.closed.not()) { "A released sampled-image owner cannot borrow textures." }
            check(terminal.not()) { "Sampled images cannot be borrowed after native shutdown begins." }
            pollInternal()
            val requested = identityDistinct(images)
            val protected = identitySet(requested)
            requested.forEach { image ->
                nextUse = Math.incrementExact(nextUse)
                val owned = owner.images[image]
                if (owned != null) {
                    hit()
                    owned.lastUse = nextUse
                    owned.entry.lastUse = nextUse
                } else {
                    val shared = activeByImage[image]
                    if (shared == null) miss() else hit()
                }
            }
            requested.forEach { image ->
                if (owner.images.containsKey(image).not()) {
                    acquire(owner, image, protected, uploaded, evicted)
                }
            }
            val available = IdentityHashMap<DrawImage, Entry>()
            requested.forEach { image ->
                owner.images[image]?.entry?.let { entry ->
                    entry.pins = Math.incrementExact(entry.pins)
                    available[image] = entry
                }
            }
            Borrow(available, available.values.toIdentitySet())
        }

    /**
     * Releases one screen owner's cache references without waiting for initialization or GUI work.
     *
     * Retired entries remain device-owned until their fences and physical destruction complete.
     */
    @JvmSynthetic
    internal fun release(owner: Owner) {
        operation {
            requireOwner(owner)
            if (owner.closed) return@operation
            owner.closed = true
            owner.images.keys
                .toList()
                .forEach { image -> removeOwnerImage(owner, image) }
            owners.remove(owner)
            pollInternal()
        }
    }

    /**
     * Reports active and retired entries still charged to the physical device budget.
     *
     * @return entries retained until native destruction acknowledgement, without polling or allocating.
     */
    @JvmSynthetic
    override fun retainedResourceCount(): Int {
        requireOwnerThread()
        return entries.size
    }

    /**
     * Reports active and retired RGBA bytes still charged to the physical device budget.
     *
     * @return bytes retained until native destruction acknowledgement, without polling or allocating.
     */
    @JvmSynthetic
    override fun retainedResourceBytes(): Long {
        requireOwnerThread()
        return retainedBytes
    }

    @JvmSynthetic
    override fun consumed() {
        operation {
            if (terminal) return@operation
            val pending = entries.filter { it.pendingGui }
            if (pending.isEmpty()) {
                pollInternal()
                return@operation
            }
            val failures = Failures()
            val completion =
                try {
                    Completion(driver.fence(), pending.size)
                } catch (failure: Throwable) {
                    failures.add(failure)
                    null
                }
            if (completion == null) {
                pending.forEach { entry ->
                    entry.pendingGui = false
                    quarantine(entry)
                }
            } else {
                pending.forEach { entry ->
                    entry.pendingGui = false
                    entry.guiCompletions.addLast(completion)
                    failures.attempt { finishSupersededGui(entry) }
                }
            }
            failures.attempt(::pollInternal)
            failures.throwIfPresent()
        }
    }

    @JvmSynthetic
    override fun poll() {
        operation { pollInternal() }
    }

    @JvmSynthetic
    override fun failedGui() {
        operation {
            if (terminal) return@operation
            entries.filter { it.pendingGui }.toList().forEach { entry ->
                entry.pendingGui = false
                quarantine(entry)
            }
            pollInternal()
        }
    }

    @JvmSynthetic
    override fun reload() {
        operation {
            check(terminal.not()) { "A terminal sampled-image cache cannot reload." }
            owners.toList().forEach { owner ->
                owner.images.keys
                    .toList()
                    .forEach { image -> removeOwnerImage(owner, image) }
            }
            pollInternal()
        }
    }

    @JvmSynthetic
    override fun beginShutdown() {
        operation {
            terminal = true
            owners.forEach { owner ->
                owner.closed = true
                owner.images.clear()
                owner.bytes = 0L
            }
            owners.clear()
            activeByImage.clear()
            entries.forEach { entry ->
                entry.references = 0
                entry.retired = true
                entry.pendingGui = false
                entry.pins = 0
                entry.image = null
            }
        }
    }

    @JvmSynthetic
    override fun closeAfterFinish() {
        operation {
            check(terminal) { "Sampled-image terminal close requires shutdown to begin." }
            val failures = Failures()
            entries.toList().forEach { entry ->
                entry.quarantined = false
                failures.attempt { finishInitialization(entry, force = true) }
                failures.attempt { finishGui(entry, force = true) }
                if (releasable(entry)) failures.attempt { requestClose(entry, retry = true) }
            }
            failures.throwIfPresent()
        }
    }

    @JvmSynthetic
    override fun acknowledgeAfterDrain() {
        operation {
            check(terminal) { "Sampled-image destruction acknowledgement requires shutdown." }
            val failures = Failures()
            entries.toList().forEach { entry -> failures.attempt { acknowledge(entry, terminal = true) } }
            if (entries.isEmpty()) failures.attempt { FabricMinecraftSampledImageDevices.acknowledge(driver, this) }
            failures.throwIfPresent()
        }
    }

    private fun acquire(
        owner: Owner,
        image: DrawImage,
        protected: Set<DrawImage>,
        uploaded: () -> Unit,
        evicted: () -> Unit,
    ) {
        val bytes = imageBytes(image)
        if (supportsImage(image).not() || OWNER_BYTES < bytes || fitOwner(owner, bytes, protected, evicted).not()) return
        val shared = activeByImage[image]
        if (shared != null) {
            addOwnerImage(owner, image, shared)
            return
        }
        if (DEVICE_BYTES < Math.addExact(retainedBytes, bytes) || DEVICE_ENTRIES <= entries.size) return
        val entry = allocate(image, bytes)
        addOwnerImage(owner, image, entry)
        uploaded()
    }

    private fun allocate(
        image: DrawImage,
        bytes: Long,
    ): Entry {
        nextEntry = Math.incrementExact(nextEntry)
        val entry = Entry(image, bytes, nextEntry)
        entries.add(entry)
        retainedBytes = Math.addExact(retainedBytes, bytes)
        activeByImage[image] = entry
        var failure: Throwable? = null
        try {
            entry.texture =
                createTexture(image) { resource ->
                    check(entry.resource == null) { "A sampled-image entry already owns native storage." }
                    entry.resource = resource
                }
            entry.uploadPixelsPending = entry.texture != null
            checkNotNull(entry.resource) { "A sampled-image texture factory did not transfer native ownership." }
        } catch (caught: Throwable) {
            failure = caught
        }
        if (failure == null) {
            try {
                entry.initializationFence = driver.fence()
            } catch (caught: Throwable) {
                entry.quarantined = true
                failure = caught
            }
        } else {
            try {
                entry.initializationFence = driver.fence()
            } catch (cleanup: Throwable) {
                entry.quarantined = true
                FabricMinecraftFailures.addSuppressed(failure, cleanup)
            }
        }
        val primary = failure
        if (primary != null) {
            retire(entry)
            throw primary
        }
        return entry
    }

    private fun fitOwner(
        owner: Owner,
        bytes: Long,
        protected: Set<DrawImage>,
        evicted: () -> Unit,
    ): Boolean {
        while (OWNER_ENTRIES <= owner.images.size || OWNER_BYTES < Math.addExact(owner.bytes, bytes)) {
            val candidate =
                owner.images.entries
                    .asSequence()
                    .filter { (image, owned) -> (image in protected).not() && owned.entry.pins == 0 }
                    .minWithOrNull(compareBy<Map.Entry<DrawImage, Owned>>({ it.value.lastUse }, { it.value.entry.sequence }))
                    ?: return false
            removeOwnerImage(owner, candidate.key)
            evicted()
        }
        return true
    }

    private fun addOwnerImage(
        owner: Owner,
        image: DrawImage,
        entry: Entry,
    ) {
        nextUse = Math.incrementExact(nextUse)
        owner.images[image] = Owned(entry, nextUse)
        owner.bytes = Math.addExact(owner.bytes, entry.bytes)
        entry.references = Math.incrementExact(entry.references)
        entry.lastUse = nextUse
    }

    private fun removeOwnerImage(
        owner: Owner,
        image: DrawImage,
    ) {
        val owned = owner.images.remove(image) ?: return
        owner.bytes = Math.subtractExact(owner.bytes, owned.entry.bytes)
        owned.entry.references -= 1
        if (owned.entry.references == 0) retire(owned.entry)
    }

    private fun retire(entry: Entry) {
        if (entry.retired) return
        val image = entry.image
        if (image != null && activeByImage[image] === entry) activeByImage.remove(image)
        entry.image = null
        entry.retired = true
    }

    private fun quarantine(entry: Entry) {
        entry.quarantined = true
        owners.toList().forEach { owner ->
            owner.images.entries
                .filter { (_, owned) -> owned.entry === entry }
                .map { (image, _) -> image }
                .forEach { image -> removeOwnerImage(owner, image) }
        }
        retire(entry)
    }

    /**
     * Releases one ordered presentation borrow and attempts nonblocking retirement.
     */
    @JvmSynthetic
    internal fun finishBorrow(borrow: Borrow) {
        operation {
            check(borrow.device === this) { "A sampled-image borrow belongs to another device." }
            check(borrow.closed.not()) { "A sampled-image borrow can close only once." }
            borrow.closed = true
            borrow.entries.forEach { entry ->
                check(0 < entry.pins) { "Sampled-image extraction pins must be balanced." }
                entry.pins -= 1
            }
            pollInternal()
        }
    }

    /**
     * Marks one borrowed identity pending at its exact direct draw position.
     */
    @JvmSynthetic
    internal fun queue(
        borrow: Borrow,
        image: DrawImage,
    ) {
        operation {
            check(borrow.device === this && borrow.closed.not()) { "A sampled-image borrow is foreign or closed." }
            val entry = checkNotNull(borrow.images[image]) { "An unavailable sampled image cannot be queued." }
            check(0 < entry.pins && entry.quarantined.not()) { "Sampled-image output requires a pinned nonquarantined entry." }
            entry.pendingGui = true
        }
    }

    /**
     * Polls initialization, GUI completion, and physical destruction without waiting.
     */
    @JvmSynthetic
    internal fun pollInternal() {
        if (terminal) return
        val failures = Failures()
        entries.toList().forEach { entry ->
            failures.attempt { finishInitialization(entry, force = false) }
            failures.attempt { finishGui(entry, force = false) }
            if (releasable(entry)) failures.attempt { requestClose(entry, retry = false) }
            if (entry.release == Release.Requested) failures.attempt { acknowledge(entry, terminal = false) }
        }
        failures.throwIfPresent()
    }

    private fun finishInitialization(
        entry: Entry,
        force: Boolean,
    ) {
        val fence = entry.initializationFence
        if (fence != null) {
            if (force.not() && fence.isSignalled().not()) return
            fence.close()
            entry.initializationFence = null
        }
        if (entry.uploadPixelsPending) {
            checkNotNull(entry.texture).releaseUploadPixels()
            entry.uploadPixelsPending = false
        }
    }

    private fun finishGui(
        entry: Entry,
        force: Boolean,
    ) {
        finishSupersededGui(entry)
        val completion = entry.guiCompletions.firstOrNull() ?: return
        if (force.not() && completion.isSignalled().not()) return
        completion.release()
        entry.guiCompletions.removeFirst()
    }

    private fun finishSupersededGui(entry: Entry) {
        while (1 < entry.guiCompletions.size) {
            entry.guiCompletions.first().release()
            entry.guiCompletions.removeFirst()
        }
    }

    private fun releasable(entry: Entry): Boolean =
        entry.retired && entry.quarantined.not() && entry.pins == 0 && entry.pendingGui.not() && entry.initializationFence == null &&
            entry.uploadPixelsPending.not() && entry.guiCompletions.isEmpty() &&
            (entry.release == Release.Live || entry.release == Release.Failed)

    private fun requestClose(
        entry: Entry,
        retry: Boolean,
    ) {
        val eligible =
            when (entry.release) {
                Release.Live -> true
                Release.Failed -> retry
                Release.Requested, Release.Destroyed -> false
            }
        if (eligible.not()) return
        entry.release = Release.Failed
        try {
            checkNotNull(entry.resource) { "A sampled-image resource was not transferred before close." }.close()
        } catch (failure: Throwable) {
            val failures = Failures(entry.releaseFailure)
            failures.add(failure)
            try {
                failures.throwIfPresent()
            } catch (primary: Throwable) {
                entry.releaseFailure = primary
                throw primary
            }
        }
        entry.releaseFailure = null
        entry.release = Release.Requested
    }

    private fun acknowledge(
        entry: Entry,
        terminal: Boolean,
    ) {
        if (entry.release != Release.Requested) {
            if (terminal) error("A sampled-image resource has no accepted terminal release.")
            return
        }
        val destroyed = checkNotNull(entry.resource).isDestroyed()
        if (destroyed.not()) {
            if (terminal) error("Sampled-image native destruction did not complete during terminal drain.")
            return
        }
        entry.release = Release.Destroyed
        entry.resource = null
        entry.texture = null
        entries.remove(entry)
        retainedBytes = Math.subtractExact(retainedBytes, entry.bytes)
    }

    private fun requireOwner(owner: Owner) {
        check(owner.device === this && owner in owners) { "A sampled-image owner belongs to another device or has expired." }
    }

    private inline fun <T> operation(action: () -> T): T {
        check(Thread.currentThread() === ownerThread) { "Sampled-image device operations require the render owner thread." }
        check(operating.not()) { "Sampled-image device operations cannot reenter callbacks." }
        operating = true
        return try {
            action()
        } finally {
            operating = false
        }
    }

    private fun identityDistinct(images: List<DrawImage>): List<DrawImage> {
        val seen = IdentityHashMap<DrawImage, Boolean>()
        return images.filter { image -> seen.put(image, true) == null }
    }

    private fun identitySet(images: List<DrawImage>): Set<DrawImage> = Collections.newSetFromMap(IdentityHashMap<DrawImage, Boolean>()).also { it.addAll(images) }

    private fun <T : Any> Collection<T>.toIdentitySet(): Set<T> = Collections.newSetFromMap(IdentityHashMap<T, Boolean>()).also { it.addAll(this) }

    private fun imageBytes(image: DrawImage): Long = Math.multiplyExact(Math.multiplyExact(image.size.width.toLong(), image.size.height.toLong()), 4L)

    private fun requireOwnerThread() {
        check(Thread.currentThread() === ownerThread) { "Sampled-image device diagnostics require the render owner thread." }
    }

    /**
     * Screen-owned cache identity whose resources remain device-owned.
     */
    internal inner class Owner internal constructor() {
        @get:JvmSynthetic
        internal val device: FabricMinecraftSampledImageDevice = this@FabricMinecraftSampledImageDevice

        @get:JvmSynthetic
        internal val images = IdentityHashMap<DrawImage, Owned>()

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var bytes = 0L

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var closed = false
    }

    /**
     * Borrowed texture lookup pinned through one complete ordered presenter call.
     */
    internal inner class Borrow internal constructor(
        @get:JvmSynthetic
        internal val images: IdentityHashMap<DrawImage, Entry>,
        @get:JvmSynthetic
        internal val entries: Set<Entry>,
    ) : AutoCloseable {
        @get:JvmSynthetic
        internal val device: FabricMinecraftSampledImageDevice = this@FabricMinecraftSampledImageDevice

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var closed = false

        /**
         * Returns the cached texture for [image], or null when bounded capacity requires portable fallback.
         */
        @JvmSynthetic
        internal fun texture(image: DrawImage): FabricMinecraftPortableTexture? = images[image]?.texture

        /**
         * Marks one available image as referenced by the native GUI queue before its draw command is emitted.
         */
        @JvmSynthetic
        internal fun queued(image: DrawImage) {
            queue(this, image)
        }

        /**
         * Releases every extraction pin without waiting for GUI consumption.
         */
        @JvmSynthetic
        override fun close() {
            finishBorrow(this)
        }
    }

    /**
     * One screen reference to a device entry and its deterministic LRU position.
     */
    internal class Owned(
        @get:JvmSynthetic
        internal val entry: Entry,
        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var lastUse: Long,
    )

    /**
     * One active or retired device-budgeted native image entry.
     */
    internal class Entry(
        image: DrawImage,
        @get:JvmSynthetic
        internal val bytes: Long,
        @get:JvmSynthetic
        internal val sequence: Long,
    ) {
        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var image: DrawImage? = image

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var resource: NativeGuiResource? = null

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var texture: FabricMinecraftPortableTexture? = null

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var initializationFence: NativeCanvasFence? = null

        @get:JvmSynthetic
        internal val guiCompletions = ArrayDeque<Completion>()

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var uploadPixelsPending = false

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var references = 0

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var pins = 0

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var pendingGui = false

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var quarantined = false

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var retired = false

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var lastUse = 0L

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var release = Release.Live

        @get:JvmSynthetic
        @set:JvmSynthetic
        internal var releaseFailure: Throwable? = null
    }

    /**
     * Native resource release phase retained across ordinary and terminal retries.
     */
    internal enum class Release {
        /**
         * The resource remains usable and no close request has run.
         */
        Live,

        /**
         * The last close request failed and may be retried only during terminal cleanup.
         */
        Failed,

        /**
         * Close was accepted while physical destruction may still be pending.
         */
        Requested,

        /**
         * Physical native destruction was acknowledged and the reservation can be removed.
         */
        Destroyed,
    }

    /**
     * One shared GUI completion fence referenced by every entry in its consumed batch.
     */
    internal class Completion(
        private val fence: NativeCanvasFence,
        private var references: Int,
    ) {
        /**
         * Reports whether the protected batch has completed without changing reference ownership.
         */
        @JvmSynthetic
        internal fun isSignalled(): Boolean = fence.isSignalled()

        /**
         * Releases one entry reference and closes the fence transactionally with the final reference.
         */
        @JvmSynthetic
        internal fun release() {
            check(0 < references) { "A sampled-image GUI completion reference can release only once." }
            if (references == 1) fence.close()
            references -= 1
        }
    }

    private class Failures(
        private var primary: Throwable? = null,
    ) {
        fun add(failure: Throwable) {
            val previous = primary
            if (previous == null) primary = failure else FabricMinecraftFailures.addSuppressed(previous, failure)
        }

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                add(failure)
            }
        }

        fun throwIfPresent() {
            primary?.let { throw it }
        }
    }

    private companion object {
        private const val OWNER_ENTRIES = 256
        private const val OWNER_BYTES = 64L * 1024L * 1024L
        private const val DEVICE_ENTRIES = 512
        private const val DEVICE_BYTES = 128L * 1024L * 1024L
    }
}
