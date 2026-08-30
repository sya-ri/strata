package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Device-owned lifetime registry for complete portable GUI resource generations.
 *
 * Each stable presenter may retain at most three sets and this device at most 64, independently of native Canvas target permits.
 * A set contains at most one resource for each exact positive layer extent supplied before allocation.
 * Cache release only requests retirement; initialization, extraction pins, actual GUI consumption, and physical destruction independently control release.
 * Resources and fences remain here rather than in screens or immutable core frames, including after partial initialization or GUI failure.
 * Every call belongs to the constructing render thread, and ordinary operations never wait for completion.
 */
@InternalStrataRuntimeApi
// The explicit resource protocol has independent phase operations; arbitrary native failures require best-effort cleanup.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught")
public class NativeGuiResources internal constructor(
    private val driver: NativeCanvasDriver,
    private val deviceId: Long,
    private val deviceIdle: () -> Boolean,
) {
    private val ownerThread = Thread.currentThread()
    private val sets = LinkedHashMap<Long, ResourceSet>()
    private var nextOwner = 0L
    private var nextSet = 0L
    private var terminal = false
    private var operating = false

    /**
     * Creates one immutable presenter identity without reserving storage or retaining the presenter.
     *
     * Retain this identity across cache release and reattachment so retired generations still count toward its three-set limit.
     *
     * @return a new identity valid only for this device.
     * @throws IllegalStateException off the render thread, during another device callback, or after shutdown.
     * @throws ArithmeticException when the device's identity space is exhausted.
     */
    public fun createOwnerId(): NativeGuiResourceOwnerId =
        operation {
            requireRunning()
            nextOwner = Math.incrementExact(nextOwner)
            NativeGuiResourceOwnerId(deviceId, nextOwner)
        }

    /**
     * Reserves one complete portable generation before the caller allocates any native resource.
     *
     * The copied layer extents bound both resource count and each allocation's shape; the adapter must allocate them in this order.
     * An empty extent list is valid and still requires sealing after any attempted initialization.
     * Full quotas fail before allocation or output, without changing the previous presentation or native Canvas's independent fallback policy.
     *
     * @param ownerId stable identity returned by this device.
     * @param extents exact positive physical layer sizes, copied without retaining the caller's collection.
     * @return an unsealed token whose permit survives every incomplete release path.
     * @throws IllegalArgumentException for nonpositive extents.
     * @throws ArithmeticException when a layer's pixel count or the generation identity cannot be represented.
     * @throws IllegalStateException for foreign identities, exhausted quotas, reentry, or terminal/off-thread access.
     */
    public fun reserve(
        ownerId: NativeGuiResourceOwnerId,
        extents: List<IntSize>,
    ): NativeGuiResourceSet =
        operation {
            requireRunning()
            check(ownerId.deviceId == deviceId && 0L < ownerId.value && ownerId.value <= nextOwner) { "Portable GUI presenter identity is foreign." }
            val copied = extents.toList()
            copied.forEach { size ->
                require(0 < size.width && 0 < size.height) { "Portable GUI resource extents must be positive." }
                Math.multiplyExact(size.width, size.height)
            }
            pollInternal()
            check(sets.size < 64 && sets.values.count { it.owner == ownerId.value } < 3) { "Portable GUI resource-set capacity is exhausted before presentation." }
            nextSet = Math.incrementExact(nextSet)
            NativeGuiResourceSet(deviceId, nextSet).also { token ->
                sets[token.value] = ResourceSet(token, ownerId.value, copied)
            }
        }

    /**
     * Transfers one layer resource immediately after allocation, before initialization or upload may fail.
     *
     * A partially allocated layer transfers through the same contract and may then throw its original initialization failure.
     * The caller still seals the whole set in a finally block before requesting retirement.
     * The resource must match the next reserved extent and must not also belong to another set or owner.
     *
     * @param set owned unsealed generation, including one whose cache already requested retirement.
     * @param resource exclusively owned native/CPU storage; ownership transfers only when this method returns normally.
     * @throws IllegalStateException for a sealed, foreign, full, expired, terminal, or off-thread set.
     */
    public fun add(
        set: NativeGuiResourceSet,
        resource: NativeGuiResource,
    ): Unit =
        operation {
            requireRunning()
            val record = requireSet(set)
            check(record.sealed.not() && record.resources.size < record.extents.size) { "Portable GUI resources must match the reserved unsealed layer count." }
            check(sets.values.none { candidate -> candidate.resources.any { it.resource === resource } }) { "A portable GUI resource cannot have multiple owners." }
            record.resources += Resource(resource)
        }

    /**
     * Seals initialization and records its completion fence after all attempted allocations and uploads.
     *
     * Call exactly once in a finally block even when no resource was added or only part of the reserved list was initialized.
     * A partial list may retire safely but cannot begin GUI use.
     * Failed fence creation or backend-required submission quarantines the entire set until terminal device completion; it never frees the permit or hides the failure.
     *
     * @param set owned unsealed generation.
     * @throws Throwable when fencing or independently eligible cleanup fails.
     * @throws IllegalStateException for a foreign, expired, already sealed, terminal, or off-thread set.
     */
    public fun seal(set: NativeGuiResourceSet): Unit =
        operation {
            requireRunning()
            val record = requireSet(set)
            check(record.sealed.not()) { "Portable GUI initialization was already sealed." }
            record.sealed = true
            try {
                record.initializationFence = driver.fence()
            } catch (failure: Throwable) {
                record.quarantined = true
                throw failure
            }
            pollInternal()
        }

    /**
     * Pins a complete initialized generation before any callback or intermediate native flush can release its cache.
     *
     * Multiple read-only uses may share unchanged pixels; initialization or in-place uploads after sealing are forbidden.
     * Each successful call must be paired with [endUse] in a finally block.
     *
     * @param set complete live generation whose immutable layer handles were captured before GUI presentation.
     * @throws IllegalStateException for incomplete, retired, quarantined, foreign, expired, terminal, or off-thread access.
     */
    public fun beginUse(set: NativeGuiResourceSet): Unit =
        operation {
            requireRunning()
            val record = requireSet(set)
            check(record.sealed && record.resources.size == record.extents.size) { "Portable GUI use requires a complete sealed generation." }
            check(record.retired.not() && record.quarantined.not()) { "A retired or quarantined portable GUI generation cannot begin another use." }
            record.pins = Math.incrementExact(record.pins)
        }

    /**
     * Marks the pinned generation as potentially referenced immediately before each native GUI blit.
     *
     * This remains valid after reentrant cache release while the original use is pinned.
     * A later blit after an intermediate consumer marks the set pending again, so an earlier fence cannot release it.
     * Failed or partial blits remain protected until the device's actual-consumer or failed-GUI hook settles them.
     *
     * @param set complete generation inside a successful [beginUse]/[endUse] pair.
     * @throws IllegalStateException for unpinned, foreign, expired, terminal, or off-thread access.
     */
    public fun queued(set: NativeGuiResourceSet): Unit =
        operation {
            requireRunning()
            val record = requireSet(set)
            check(0 < record.pins && record.quarantined.not()) { "Portable GUI output requires an active nonquarantined extraction pin." }
            record.pendingGui = true
        }

    /**
     * Releases one extraction pin after the complete ordered presenter call returns or fails.
     *
     * Retirement still waits for initialization and every later GUI consumption fence.
     * If terminal shutdown already discarded the queues, unwinding an old use is harmless and issues no native work.
     *
     * @param set generation from a successful [beginUse].
     * @throws Throwable when eligible nonblocking cleanup fails after the pin has been removed.
     * @throws IllegalStateException off-thread, for foreign tokens, or for unmatched live use pairs.
     */
    public fun endUse(set: NativeGuiResourceSet): Unit =
        operation {
            requireDevice(set)
            if (terminal) return@operation
            val record = requireSet(set)
            check(0 < record.pins) { "Portable GUI extraction pins must be balanced." }
            record.pins -= 1
            pollInternal()
        }

    /**
     * Requests retirement while letting the screen immediately clear every cache and resource reference.
     *
     * This idempotent owner-thread operation is safe from another resource's close callback and requires no new permit.
     * It never cancels already pinned output or closes an unsealed/queued/in-flight resource.
     * Unsealed initialization still requires [seal], including after reentrant release; terminal cleanup handles abandoned initialization.
     *
     * @param set owned generation, including an already physically destroyed token.
     * @throws Throwable after independent eligible cleanup when retirement fails.
     * @throws IllegalStateException off-thread or for a foreign token.
     */
    public fun release(set: NativeGuiResourceSet) {
        requireOwner()
        requireDevice(set)
        val record = sets[set.value] ?: return
        check(record.token === set) { "Portable GUI resource-set token is foreign." }
        record.retired = true
        if (terminal.not() && operating.not() && deviceIdle()) operation { pollInternal() }
    }

    /**
     * Reports active, retired, partially initialized, quarantined, and asynchronously destroying portable sets.
     *
     * This owner-thread diagnostic neither polls nor touches a native API and is safe after device shutdown.
     * Its count is separate from [NativeCanvasDevice.retainedTargetCount].
     *
     * @return permits retained until all resources in each set acknowledge physical destruction.
     * @throws IllegalStateException off the render thread.
     */
    public fun retainedSetCount(): Int {
        requireOwner()
        return sets.size
    }

    /**
     * Reports callback reentry to the containing device without invoking native code.
     *
     * The device reads this only after checking its owner thread, before entering a guarded operation.
     * No resource ownership or lifecycle state changes and the read cannot fail.
     */
    internal val isOperating: Boolean
        get() = operating

    /**
     * Settles pending portable uses only after the version-owned actual GUI consumer has consumed them.
     *
     * The containing device calls this on its render thread inside its operation guard.
     * One fence issued at that boundary covers every pending set; superseded fences release without waiting because the replacement covers their earlier work.
     * Fence creation or backend-required submission failure quarantines every affected set and propagates after independent old-fence releases are attempted.
     */
    internal fun consumed(): Unit =
        operation(fromDevice = true) {
            if (terminal) return@operation
            val pending = sets.values.filter { it.pendingGui }
            if (pending.isEmpty()) return@operation
            val failures = CanvasFailures()
            val completion =
                try {
                    Completion(driver.fence(), pending.size)
                } catch (failure: Throwable) {
                    pending.forEach { it.quarantined = true }
                    failures.add(failure)
                    null
                }
            pending.forEach { record ->
                record.pendingGui = false
                val previous = record.guiCompletion
                record.guiCompletion = completion
                if (previous != null) failures.attempt(previous::release)
            }
            failures.throwIfPresent()
        }

    /**
     * Quarantines potentially consumed portable resources after an incomplete native GUI operation without waiting.
     *
     * Called only by the owning device on the render thread, it retains every affected permit until terminal queue discard and completion.
     * No native callbacks run and no additional storage is reserved.
     */
    internal fun failedGui(): Unit =
        operation(fromDevice = true) {
            sets.values.filter { it.pendingGui }.forEach { record ->
                record.pendingGui = false
                record.quarantined = true
            }
        }

    /**
     * Polls completion and retirement independently of screens while preserving the first failure across all sets.
     *
     * The containing device invokes this on its render thread; terminal owners no longer poll native APIs.
     * Every independently eligible release is attempted before native query or cleanup failures propagate.
     */
    internal fun poll(): Unit = operation(fromDevice = true) { if (terminal.not()) pollInternal() }

    /**
     * Stops acquisition before terminal callbacks after the caller has discarded every native GUI queue.
     *
     * The device calls this on the owner thread before terminal completion submits as required and waits for recorded work.
     * Pins and queued references are dropped without closing resources, allowing old extraction finally blocks to unwind harmlessly.
     */
    internal fun beginShutdown(): Unit =
        operation(fromDevice = true) {
            terminal = true
            sets.values.forEach { record ->
                record.retired = true
                record.pendingGui = false
                record.pins = 0
            }
        }

    /**
     * Requests every owned release after the shared driver has completed submitted work, before its destruction drain.
     *
     * Called on the owner thread only after [beginShutdown], it also handles incomplete initialization and quarantined sets.
     * It attempts every independent fence and resource close before throwing the first failure, without releasing permits prematurely.
     */
    internal fun closeAfterFinish(): Unit =
        operation(fromDevice = true) {
            check(terminal) { "Portable GUI terminal release requires shutdown to begin first." }
            val failures = CanvasFailures()
            sets.values.toList().forEach { record ->
                failures.attempt { finishInitialization(record, force = true) }
                failures.attempt { finishGui(record, force = true) }
                record.resources.forEach { resource -> failures.attempt { requestClose(resource, retry = true) } }
            }
            failures.throwIfPresent()
        }

    /**
     * Requires physical destruction after the shared terminal drain, retaining unresolved permits and reporting every independent failure.
     *
     * The device invokes this on its render thread after all native Canvas and portable releases have been requested.
     * Successful acknowledgements release the matching permits; failed queries or incomplete destruction remain owned and are reported.
     */
    internal fun acknowledgeAfterDrain(): Unit =
        operation(fromDevice = true) {
            check(terminal) { "Portable GUI terminal acknowledgement requires shutdown." }
            val failures = CanvasFailures()
            sets.values.toList().forEach { record -> failures.attempt { acknowledge(record, terminal = true) } }
            failures.throwIfPresent()
        }

    private fun pollInternal() {
        val failures = CanvasFailures()
        sets.values.toList().forEach { record ->
            failures.attempt { finishInitialization(record, force = false) }
            failures.attempt { finishGui(record, force = false) }
            if (releasable(record)) {
                record.resources.forEach { resource -> failures.attempt { requestClose(resource, retry = false) } }
                failures.attempt { acknowledge(record, terminal = false) }
            }
        }
        failures.throwIfPresent()
    }

    private fun releasable(record: ResourceSet): Boolean {
        val initialized = record.sealed && record.initializationFence == null
        val unused = record.pins == 0 && record.pendingGui.not() && record.guiCompletion == null
        return record.retired && record.quarantined.not() && initialized && unused
    }

    private fun finishInitialization(
        record: ResourceSet,
        force: Boolean,
    ) {
        val fence = record.initializationFence ?: return
        if (force.not() && fence.isSignalled().not()) return
        record.initializationFence = null
        fence.close()
    }

    private fun finishGui(
        record: ResourceSet,
        force: Boolean,
    ) {
        val completion = record.guiCompletion ?: return
        if (force.not() && completion.isSignalled().not()) return
        record.guiCompletion = null
        completion.release()
    }

    private fun requestClose(
        resource: Resource,
        retry: Boolean,
    ) {
        val eligible =
            when (resource.release) {
                Release.Live -> true
                Release.Failed -> retry
                Release.Requested, Release.Destroyed -> false
            }
        if (eligible.not()) return
        resource.release = Release.Failed
        try {
            checkNotNull(resource.resource).close()
        } catch (failure: Throwable) {
            val failures = CanvasFailures(resource.failure)
            failures.add(failure)
            try {
                failures.throwIfPresent()
            } catch (primary: Throwable) {
                resource.failure = primary
                throw primary
            }
        }
        resource.release = Release.Requested
        resource.failure = null
    }

    private fun acknowledge(
        record: ResourceSet,
        terminal: Boolean,
    ) {
        val failures = CanvasFailures()
        record.resources.filter { it.release == Release.Requested }.forEach { resource ->
            failures.attempt {
                val destroyed = checkNotNull(resource.resource).isDestroyed()
                if (destroyed) {
                    resource.resource = null
                    resource.release = Release.Destroyed
                }
                check(terminal.not() || destroyed) { "Portable GUI resource remains physically allocated after terminal retirement drain." }
            }
        }
        if (record.resources.all { it.release == Release.Destroyed }) sets.remove(record.token.value)
        failures.throwIfPresent()
    }

    private fun requireSet(set: NativeGuiResourceSet): ResourceSet {
        requireDevice(set)
        return checkNotNull(sets[set.value]?.takeIf { it.token === set }) { "Portable GUI resource-set token is foreign or expired." }
    }

    private fun requireDevice(set: NativeGuiResourceSet) {
        check(set.deviceId == deviceId) { "Portable GUI resource-set token belongs to another device." }
    }

    private fun requireRunning() {
        check(terminal.not()) { "Portable GUI resources cannot be acquired after device shutdown begins." }
    }

    private fun requireOwner() {
        check(Thread.currentThread() === ownerThread) { "Portable GUI resources require their owning render thread." }
    }

    private inline fun <T> operation(
        fromDevice: Boolean = false,
        action: () -> T,
    ): T {
        requireOwner()
        check(operating.not() && (fromDevice || deviceIdle())) { "Portable GUI resource operations cannot reenter device callbacks." }
        operating = true
        return try {
            action()
        } finally {
            operating = false
        }
    }

    private class ResourceSet(
        val token: NativeGuiResourceSet,
        val owner: Long,
        val extents: List<IntSize>,
    ) {
        val resources = ArrayList<Resource>()
        var sealed = false
        var retired = false
        var quarantined = false
        var pins = 0
        var pendingGui = false
        var initializationFence: NativeCanvasFence? = null
        var guiCompletion: Completion? = null
    }

    private class Resource(
        var resource: NativeGuiResource?,
        var release: Release = Release.Live,
        var failure: Throwable? = null,
    )

    private enum class Release {
        Live,
        Failed,
        Requested,
        Destroyed,
    }

    private class Completion(
        private val fence: NativeCanvasFence,
        private var references: Int,
    ) {
        fun isSignalled(): Boolean = fence.isSignalled()

        fun release() {
            references -= 1
            if (references == 0) fence.close()
        }
    }
}
