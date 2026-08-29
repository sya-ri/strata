package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.component.CanvasBinding
import dev.s7a.strata.component.CanvasId
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.PaintScope
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.concurrent.atomic.AtomicLong

/**
 * Render-thread owner of bounded native canvas targets, source leases, and renderer lifetimes.
 *
 * One instance belongs to one native device, not to a screen.
 * At most three target sets per stable canvas identity and 64 sets across this device may exist, including retired and quarantined sets.
 * A permit is reserved before allocation and is released only after physical destruction is acknowledged, including asynchronous native release queues.
 * Native Canvas payloads in frames retain scalar identifiers only; the device owns every handle and producer until their distinct initialization, capture, and GUI completion fences permit cleanup.
 * Only one GUI batch may be extracted at a time; that batch must be consumed or explicitly cancelled before the next extraction.
 * Ordinary polling and screen cleanup never block on GPU completion or unconsumed GUI work.
 *
 * @param driver borrowed version-specific device operations, confined to this object's construction thread.
 */
@InternalStrataRuntimeApi
// Native callbacks may throw any Throwable; preserve the primary instance while attempting independent cleanup.
@Suppress("TooManyFunctions", "LargeClass", "TooGenericExceptionCaught")
public class NativeCanvasDevice(
    private val driver: NativeCanvasDriver,
) {
    private val ownerThread = Thread.currentThread()
    private val deviceId = identities.incrementAndGet().also { check(0L < it) { "Native canvas device identity exhausted." } }
    private val attachments = LinkedHashMap<Long, Attachment>()
    private val owners = LinkedHashSet<ProducerOwner>()
    private val targets = LinkedHashSet<TargetRecord>()
    private var nextAttachment = 0L
    private var nextGeneration = 0L
    private var nextBatch = 0L
    private var permits = 0
    private var batch: Batch? = null
    private var closed = false
    private var shutdownFailure: Throwable? = null
    private var operating = false

    /**
     * Owns portable GUI texture generations independently of this device's native Canvas target quota.
     *
     * Presenters keep only opaque set tokens and their active cache references; this owner retains retired resources through actual GUI completion and physical destruction.
     * Access and all resource operations belong to the device render thread, and terminal cleanup is shared with native targets.
     */
    public val guiResources: NativeGuiResources = NativeGuiResources(driver, deviceId) { operating.not() }

    /**
     * Creates an externally reusable description without opening a renderer or allocating a target.
     *
     * Each node opens an independent producer; the description never owns or closes the external source.
     *
     * @param producerFactory owner-thread factory called once per attachment or resource-reload generation; it must issue no GPU work and defer renderer initialization to the capture's render callback.
     * @param depth whether every target created for this source needs a depth attachment.
     * @return immutable source description whose bindings belong to this device.
     * @throws IllegalStateException when called off the device thread or after shutdown.
     */
    public fun source(
        producerFactory: () -> NativeCanvasProducer,
        depth: Boolean = false,
    ): CanvasSource {
        requireOwner()
        check(closed.not()) { "A closed native canvas device cannot create sources." }
        return CanvasSource { identity -> open(identity, producerFactory, depth) }
    }

    /**
     * Resolves native attachment requests after final layout, producing each attachment at most once.
     *
     * Every request is validated before any producer or drawing output is invoked.
     * Unavailable permits or a producer returning no capture keep the exact previous token and snapshot; an initial canvas remains transparent.
     * The supplied command list and original runtime frame are never mutated.
     *
     * @param commands complete immutable core display list, including portable commands and clips.
     * @param frameTime timestamp of this actual presentation, independent of extra host frames used for hover convergence.
     * @param scale positive resolved integer GUI scale, applied only to native target allocation.
     * @return detached immutable presentation; its targets remain pinned until consumption or cancellation.
     * @throws Throwable when validation, capture, allocation, or cleanup fails, preserving the primary exception.
     */
    public fun prepare(
        commands: List<DrawCommand>,
        frameTime: FrameTime,
        scale: Int,
    ): NativeCanvasPresentation =
        operation {
            check(closed.not()) { "A closed native canvas device cannot prepare a frame." }
            check(batch == null) { "The previous native canvas GUI batch has not been consumed or cancelled." }
            require(0 < scale) { "Canvas GUI scale must be positive." }
            val requests = validateRequests(commands, scale)
            pollInternal()
            val prepared = LinkedHashMap<Attachment, TargetRecord?>()
            try {
                requests.forEach { (attachment, size) ->
                    check(attachments[attachment.id] === attachment) { "Canvas attachment expired during preparation." }
                    val candidate = prepareAttachment(attachment, size, scale, frameTime)
                    prepared[attachment] = candidate ?: attachment.committed
                }
                requests.keys.forEach { attachment ->
                    check(attachments[attachment.id] === attachment) { "Canvas attachment expired during preparation." }
                }
            } catch (failure: Throwable) {
                val failures = CanvasFailures(failure)
                failures.attempt { pollInternal() }
                failures.throwIfPresent()
            }
            prepared.forEach { (attachment, record) -> attachment.committed = record }
            val selected = prepared.values.filterNotNull().toSet()
            selected.forEach { it.pendingGui = true }
            val resolved =
                commands.mapNotNull { command ->
                    val request = (command as? DrawCommand.Platform)?.command as? NativeCanvasRequest
                    if (request == null) {
                        command
                    } else {
                        val attachment = checkNotNull(attachments[request.attachmentId])
                        prepared[attachment]?.let { DrawCommand.Platform(checkNotNull(it.token), command.bounds) }
                    }
                }
            val receipts = selected.mapNotNull { record -> record.snapshot?.let { NativeCanvasSnapshot(checkNotNull(record.token), it) } }
            nextBatch = Math.incrementExact(nextBatch)
            NativeCanvasPresentation(
                deviceId,
                nextBatch,
                resolved,
                receipts,
                hasUncommittedCanvases = prepared.values.any { it == null },
            ).also { presentation ->
                batch = Batch(presentation, selected)
            }
        }

    /**
     * Validates and marks an entire prepared presentation as queued before the first GUI command is emitted.
     *
     * After this transition, screen detachment cannot release its resources; the independent GUI-consumption hook must settle it.
     *
     * @param presentation exactly the current device-owned batch.
     * @throws IllegalStateException for foreign, expired, detached, or already queued batches, before partial output.
     */
    public fun queue(presentation: NativeCanvasPresentation): Unit =
        operation {
            val current = requireBatch(presentation)
            check(current.queued.not()) { "Native canvas presentation was already queued." }
            current.targets.forEach { check(it.owner.retired.not()) { "Native canvas attachment expired before GUI submission." } }
            current.queued = true
        }

    /**
     * Borrows one target while the versioned presenter emits its queued GUI draw command.
     *
     * The returned target must not escape that native callback or be stored in a core frame.
     *
     * @param presentation the currently queued presentation.
     * @param token immutable generation identifier belonging to that presentation.
     * @return borrowed matching target, valid through this batch's native GUI consumption.
     * @throws IllegalStateException when either identity is foreign or expired.
     */
    public fun target(
        presentation: NativeCanvasPresentation,
        token: NativeCanvasToken,
    ): NativeCanvasTarget =
        operation {
            val current = requireBatch(presentation)
            check(current.queued) { "Native canvas presentation has not been queued." }
            check(token.deviceId == deviceId) { "Native canvas token belongs to another device." }
            checkNotNull(current.targets.singleOrNull { it.token === token }) { "Native canvas generation is foreign or expired." }.target
        }

    /**
     * Cancels a prepared batch that has emitted no GUI work, without waiting for any capture fence.
     *
     * @param presentation the current unqueued presentation.
     * @throws IllegalStateException when the batch is foreign, expired, or already queued.
     * @throws Throwable when nonblocking cleanup fails after all pins have been removed.
     */
    public fun cancel(presentation: NativeCanvasPresentation): Unit =
        operation {
            val current = requireBatch(presentation)
            check(current.queued.not()) { "Queued GUI work must be consumed or discarded by its native owner." }
            batch = null
            current.targets.forEach { it.pendingGui = false }
            pollInternal()
        }

    /**
     * Settles the queued batch after the native GUI consumer has consumed its draw queue at the version-owned boundary.
     *
     * This must run from the version-owned consumption boundary, including its failure finally path, never from extraction.
     * The new fence covers all earlier uses on the same queue, so obsolete GUI fences can be released without waiting.
     * Portable GUI resource generations are settled at the same consumer boundary, including presentations with no native Canvas commands.
     * With no queued native or portable work this is a nonblocking cleanup poll.
     *
     * @throws Throwable when fence creation or cleanup fails; affected targets remain quarantined until device shutdown.
     */
    public fun consumed(): Unit =
        operation {
            val current = batch
            val failures = CanvasFailures()
            if (current != null && current.queued) {
                batch = null
                val completion =
                    try {
                        current.targets.takeIf { it.isNotEmpty() }?.let { Completion(driver.fence(), it.size) }
                    } catch (failure: Throwable) {
                        current.targets.forEach { it.quarantined = true }
                        failures.add(failure)
                        null
                    }
                current.targets.forEach { record ->
                    record.pendingGui = false
                    val previous = record.guiCompletion
                    record.guiCompletion = completion
                    if (previous != null) failures.attempt { previous.release() }
                }
            }
            failures.attempt { guiResources.consumed() }
            failures.attempt { pollInternal() }
            failures.throwIfPresent()
        }

    /**
     * Advances initialization, capture-lease, retired-target, and renderer cleanup without waiting, including when no Strata screen is visible.
     *
     * Once terminal shutdown begins this becomes a no-op, including after a failed finish or release.
     * Failed terminal resources remain quarantined, and later callbacks never query a device that may already have been destroyed.
     *
     * @throws Throwable after attempting every independently eligible cleanup; the first failure remains primary.
     */
    public fun poll(): Unit = operation { pollInternal() }

    /**
     * Quarantines queued targets after a GUI consumer failure when complete consumption cannot be established.
     *
     * No target or renderer is released until terminal device completion and queue discard.
     * This owner-thread failure path neither waits nor reserves another lifetime permit.
     */
    public fun failedGui(): Unit =
        operation {
            val current = batch
            if (current != null) {
                check(current.queued) { "An unqueued canvas batch must be cancelled, not reported as a GUI failure." }
                batch = null
                current.targets.forEach {
                    it.pendingGui = false
                    it.quarantined = true
                }
            }
            guiResources.failedGui()
            pollInternal()
        }

    /**
     * Invalidates active derived generations on a resource reload while preserving external sources and stable canvas limits.
     *
     * Old renderers and targets retire behind their own fences; a fresh renderer opens lazily on the next presentation.
     *
     * @throws Throwable when eligible cleanup fails, after every active attachment has been invalidated.
     */
    public fun reload(): Unit =
        operation {
            check(closed.not()) { "A closed native canvas device cannot reload." }
            attachments.values.forEach { attachment ->
                attachment.owner?.retired = true
                attachment.owner = null
                attachment.committed = null
            }
            pollInternal()
        }

    /**
     * Destroys device-owned resources after the caller has discarded every unconsumed native GUI queue.
     *
     * Shutdown immediately stops acquisition and first asks the driver to submit as required and complete every recorded GPU command.
     * If completion cannot be established, no resource release is attempted; targets, fences, leases, renderers, and portable GUI resources remain quarantined because their last GPU use is unproven.
     * After completion is established, all independent releases are attempted and deferred destruction is drained once before physical target and portable resource acknowledgements release their separate permits.
     * A failed release or missing physical acknowledgement retains its permit and quarantined resource instead of claiming successful release.
     * Every failure remains sticky on repeated shutdown calls; independent cleanup continues after individual release or drain failures once completion has been established.
     *
     * @throws Throwable when terminal completion fails before release, or when later cleanup fails after the remaining independent cleanup has been attempted.
     */
    public fun closeAfterGuiDiscarded(): Unit =
        operation {
            shutdownFailure?.let { throw it }
            if (closed) return@operation
            closed = true
            guiResources.beginShutdown()
            attachments.values.forEach { it.owner?.retired = true }
            attachments.clear()
            batch?.targets?.forEach { it.pendingGui = false }
            batch = null
            try {
                driver.finish()
                val failures = CanvasFailures()
                targets.toList().forEach { record ->
                    record.quarantined = false
                    failures.attempt { finishInitialization(record, force = true) }
                    failures.attempt { finishCapture(record, force = true) }
                    failures.attempt { finishGui(record, force = true) }
                    failures.attempt { requestDestruction(record, retry = true) }
                }
                owners.toList().forEach { owner -> failures.attempt { closeProducer(owner) } }
                failures.attempt { guiResources.closeAfterFinish() }
                failures.attempt { driver.drainRetirements() }
                targets.toList().forEach { record -> failures.attempt { acknowledgeDestruction(record, terminal = true) } }
                failures.attempt { guiResources.acknowledgeAfterDrain() }
                failures.throwIfPresent()
            } catch (failure: Throwable) {
                shutdownFailure = failure
                throw failure
            }
        }

    /**
     * Reports reserved active and retired target sets on the owning thread for deterministic capacity verification.
     *
     * @return permits retained until successful physical destruction, including quarantined failures.
     */
    public fun retainedTargetCount(): Int {
        requireOwner()
        return permits
    }

    private fun open(
        identity: CanvasId,
        factory: () -> NativeCanvasProducer,
        depth: Boolean,
    ): CanvasBinding =
        operation {
            check(closed.not()) { "A closed native canvas device cannot open an attachment." }
            nextAttachment = Math.incrementExact(nextAttachment)
            val owner = ProducerOwner(factory())
            owners.add(owner)
            val attachment = Attachment(nextAttachment, identity.value, factory, depth, owner)
            attachments[attachment.id] = attachment
            Binding(attachment)
        }

    private fun validateRequests(
        commands: List<DrawCommand>,
        scale: Int,
    ): Map<Attachment, IntSize> {
        val requests = LinkedHashMap<Attachment, IntSize>()
        var clips = 0
        commands.forEach { command ->
            when (command) {
                is DrawCommand.PushClip -> {
                    clips = Math.incrementExact(clips)
                }

                DrawCommand.PopClip -> {
                    check(0 < clips) { "Canvas frame has an unmatched clip pop." }
                    clips -= 1
                }

                is DrawCommand.Platform -> {
                    when (val payload = command.command) {
                        is NativeCanvasRequest -> {
                            check(payload.deviceId == deviceId) { "Canvas request belongs to another native device." }
                            val attachment = checkNotNull(attachments[payload.attachmentId]) { "Canvas attachment is expired." }
                            val size = command.bounds.size
                            physicalSize(size, scale)
                            val previous = requests.put(attachment, size)
                            check(previous == null || previous == size) { "One canvas attachment cannot use different extents in one presentation." }
                        }

                        is NativeCanvasToken -> {
                            error("A prepared canvas generation cannot be submitted as a live attachment request.")
                        }

                        else -> {}
                    }
                }

                else -> {}
            }
        }
        check(clips == 0) { "Canvas frame has an unmatched clip push." }
        return requests
    }

    private fun prepareAttachment(
        attachment: Attachment,
        logicalSize: IntSize,
        scale: Int,
        time: FrameTime,
    ): TargetRecord? {
        val size = physicalSize(logicalSize, scale)
        val record = availableTarget(attachment, size) ?: return null
        check(attachments[attachment.id] === attachment) { "Canvas attachment expired before capture." }
        val producer = checkNotNull(record.owner.producer)
        val capture = producer.capture() ?: return null
        record.capture = capture
        val failures = CanvasFailures()
        var snapshot: DrawImage? = null
        failures.attempt {
            check(attachments[attachment.id] === attachment) { "Canvas attachment expired before rendering." }
            snapshot = capture.render(record.target, logicalSize, time)
            check(snapshot == null || snapshot?.size == size) { "Canvas capture snapshot must match the normalized physical target extent." }
        }
        failures.attempt {
            try {
                record.captureFence = driver.fence()
            } catch (failure: Throwable) {
                record.quarantined = true
                throw failure
            }
        }
        failures.throwIfPresent()
        nextGeneration = Math.incrementExact(nextGeneration)
        record.token = NativeCanvasToken(deviceId, attachment.id, nextGeneration, size)
        record.snapshot = snapshot
        return record
    }

    private fun availableTarget(
        attachment: Attachment,
        size: IntSize,
    ): TargetRecord? {
        val existingOwner = attachment.owner
        targets
            .firstOrNull {
                it.canvasId == attachment.canvasId && it.owner === existingOwner && it.target.size == size && reusable(it)
            }?.let { return it }
        targets.firstOrNull { it.canvasId == attachment.canvasId && reusable(it) }?.let(::destroy)
        if (64 <= permits) targets.firstOrNull(::reusable)?.let(::destroy)
        if (64 <= permits || 3 <= targets.count { it.canvasId == attachment.canvasId }) return null
        permits += 1
        val owner = acquireOwner(attachment)
        return allocateReservedTarget(attachment, size, owner)
    }

    private fun acquireOwner(attachment: Attachment): ProducerOwner =
        try {
            attachment.owner ?: ProducerOwner(attachment.factory()).also {
                owners.add(it)
                it.retired = attachments[attachment.id] !== attachment
                attachment.owner = it
            }
        } catch (failure: Throwable) {
            permits -= 1
            throw failure
        }

    private fun allocateReservedTarget(
        attachment: Attachment,
        size: IntSize,
        owner: ProducerOwner,
    ): TargetRecord {
        val target =
            try {
                driver.createTarget(size, attachment.depth)
            } catch (failure: NativeCanvasAllocationFailure) {
                val record = TargetRecord(attachment.canvasId, owner, failure.target)
                record.quarantined = true
                if (failure.releaseRequested) record.release = TargetRelease.Requested
                targets.add(record)
                val failures = CanvasFailures(failure.failure)
                failures.attempt { record.initializationFence = driver.fence() }
                throw failure.failure
            } catch (failure: Throwable) {
                permits -= 1
                throw failure
            }
        return TargetRecord(attachment.canvasId, owner, target).also { record ->
            targets.add(record)
            initializeTarget(record, size)
        }
    }

    private fun initializeTarget(
        record: TargetRecord,
        size: IntSize,
    ) {
        try {
            record.initializationFence = driver.fence()
            check(record.target.size == size) { "Native canvas driver allocated an unexpected target extent." }
        } catch (failure: Throwable) {
            record.quarantined = true
            throw failure
        }
    }

    private fun reusable(record: TargetRecord): Boolean =
        record.pendingGui.not() && record.quarantined.not() && record.release == TargetRelease.Live && record.initializationFence == null &&
            record.capture == null && record.captureFence == null && record.guiCompletion == null &&
            attachments.values.none { it.committed === record }

    private fun pollInternal() {
        if (closed) return
        val failures = CanvasFailures()
        targets.toList().forEach { record ->
            failures.attempt { finishInitialization(record, force = false) }
            failures.attempt { finishCapture(record, force = false) }
            failures.attempt { finishGui(record, force = false) }
            if (record.release == TargetRelease.Requested && record.quarantined.not()) {
                failures.attempt { acknowledgeDestruction(record) }
            } else if (record.owner.retired && reusable(record)) {
                failures.attempt { destroy(record) }
            }
        }
        owners.toList().filter { it.retired && targets.none { record -> record.owner === it } }.forEach { owner ->
            failures.attempt { closeProducer(owner) }
        }
        failures.attempt { guiResources.poll() }
        failures.throwIfPresent()
    }

    private fun finishInitialization(
        record: TargetRecord,
        force: Boolean,
    ) {
        val fence = record.initializationFence ?: return
        if (force.not() && fence.isSignalled().not()) return
        record.initializationFence = null
        fence.close()
    }

    private fun finishCapture(
        record: TargetRecord,
        force: Boolean,
    ) {
        val fence = record.captureFence
        if (force.not() && (fence == null || fence.isSignalled().not())) return
        record.captureFence = null
        val capture = record.capture
        record.capture = null
        val failures = CanvasFailures()
        if (fence != null) failures.attempt { fence.close() }
        if (capture != null) failures.attempt { capture.close() }
        failures.throwIfPresent()
    }

    private fun finishGui(
        record: TargetRecord,
        force: Boolean,
    ) {
        val completion = record.guiCompletion ?: return
        if (force.not() && completion.isSignalled().not()) return
        record.guiCompletion = null
        completion.release()
    }

    private fun destroy(record: TargetRecord) {
        requestDestruction(record)
        acknowledgeDestruction(record)
    }

    private fun requestDestruction(
        record: TargetRecord,
        retry: Boolean = false,
    ) {
        if (record.release == TargetRelease.Requested || (record.release == TargetRelease.Failed && retry.not())) return
        record.release = TargetRelease.Failed
        record.snapshot = null
        try {
            record.target.close()
        } catch (failure: Throwable) {
            val failures = CanvasFailures(record.destroyFailure)
            failures.add(failure)
            try {
                failures.throwIfPresent()
            } catch (primary: Throwable) {
                record.destroyFailure = primary
                throw primary
            }
        }
        record.release = TargetRelease.Requested
        record.destroyFailure = null
    }

    private fun acknowledgeDestruction(
        record: TargetRecord,
        terminal: Boolean = false,
    ) {
        if (record.release != TargetRelease.Requested) return
        val destroyed = record.target.isDestroyed()
        if (destroyed && targets.remove(record)) permits -= 1
        check(terminal.not() || destroyed) { "Native canvas target remains physically allocated after terminal retirement drain." }
    }

    private fun closeProducer(owner: ProducerOwner) {
        val producer = owner.producer
        owner.producer = null
        owners.remove(owner)
        producer?.close()
    }

    private fun detach(attachment: Attachment) {
        requireOwner()
        if (attachments.remove(attachment.id) == null) return
        attachment.committed = null
        attachment.owner?.retired = true
        if (operating.not() && guiResources.isOperating.not()) operation { pollInternal() }
    }

    private fun requireBatch(presentation: NativeCanvasPresentation): Batch {
        check(presentation.deviceId == deviceId) { "Native canvas presentation belongs to another device." }
        val current = checkNotNull(batch) { "Native canvas presentation has expired." }
        check(current.presentation === presentation) { "Native canvas presentation is not the active batch." }
        return current
    }

    private fun physicalSize(
        logical: IntSize,
        scale: Int,
    ): IntSize {
        require(0 < logical.width && 0 < logical.height) { "Native canvas extent must be positive." }
        val width = Math.multiplyExact(logical.width, scale)
        val height = Math.multiplyExact(logical.height, scale)
        Math.multiplyExact(width, height)
        return IntSize(width, height)
    }

    private fun requireOwner() {
        check(Thread.currentThread() === ownerThread) { "Native canvas operations must run on their owning render thread." }
    }

    private inline fun <T> operation(action: () -> T): T {
        requireOwner()
        check(operating.not() && guiResources.isOperating.not()) { "Native canvas device operations cannot reenter callbacks." }
        operating = true
        return try {
            action()
        } finally {
            operating = false
        }
    }

    private inner class Binding(
        private val attachment: Attachment,
    ) : CanvasBinding {
        private val request = NativeCanvasRequest(deviceId, attachment.id)
        private var released = false

        override fun paint(scope: PaintScope) {
            requireOwner()
            check(released.not()) { "Native canvas binding is closed." }
            scope.drawPlatform(request, IntRect(0, 0, scope.size.width, scope.size.height))
        }

        override fun close() {
            requireOwner()
            if (released) return
            released = true
            detach(attachment)
        }
    }

    private class Attachment(
        val id: Long,
        val canvasId: Long,
        val factory: () -> NativeCanvasProducer,
        val depth: Boolean,
        var owner: ProducerOwner?,
        var committed: TargetRecord? = null,
    )

    private class ProducerOwner(
        var producer: NativeCanvasProducer?,
        var retired: Boolean = false,
    )

    private class TargetRecord(
        val canvasId: Long,
        val owner: ProducerOwner,
        val target: NativeCanvasTarget,
    ) {
        var token: NativeCanvasToken? = null
        var snapshot: DrawImage? = null
        var initializationFence: NativeCanvasFence? = null
        var capture: NativeCanvasCapture? = null
        var captureFence: NativeCanvasFence? = null
        var guiCompletion: Completion? = null
        var pendingGui = false
        var quarantined = false
        var release = TargetRelease.Live
        var destroyFailure: Throwable? = null
    }

    private enum class TargetRelease {
        Live,
        Failed,
        Requested,
    }

    private class Batch(
        val presentation: NativeCanvasPresentation,
        val targets: Set<TargetRecord>,
        var queued: Boolean = false,
    )

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

    private companion object {
        val identities = AtomicLong()
    }
}
