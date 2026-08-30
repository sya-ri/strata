package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.CanvasSource
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Drives native lifetime accounting through actual retained Canvas nodes and deterministic nonblocking device probes.
 *
 * This fixture tests the common resource protocol, not GPU pixel execution or versioned adapter behavior.
 * All methods run on the constructing test thread and the fixture owns its trees and device until close.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeCanvasFixture : AutoCloseable {
    val driver: Driver = Driver()
    val device: NativeCanvasDevice = NativeCanvasDevice(driver)
    val producers: MutableList<Producer> = ArrayList()
    val identities: MutableList<Long> = ArrayList()
    private val trees: MutableList<UiTree> = ArrayList()

    /**
     * Creates an externally reusable source that records real node identities and opens independent test producers.
     *
     * @param depth whether targets require a depth attachment.
     * @return source whose lifetime remains independent of every attachment.
     */
    fun source(depth: Boolean = false): CanvasSource {
        val delegate = device.source({ Producer().also(producers::add) }, depth)
        return CanvasSource { identity ->
            identities += identity.value
            delegate.open(identity)
        }
    }

    /**
     * Creates a real retained tree, opening the supplied source through the public Canvas SPI.
     *
     * @param source externally owned source.
     * @param size positive logical Canvas extent.
     * @return fixture-owned attached tree; no native capture has run yet.
     */
    fun tree(
        source: CanvasSource = source(),
        size: IntSize = IntSize(2, 2),
    ): UiTree =
        UiTree().also { tree ->
            trees += tree
            tree.update(evaluateComponentTree { Canvas(source, size) })
        }

    /**
     * Produces the real cached core display list without invoking native capture.
     *
     * @param tree fixture-owned tree.
     * @param size current logical Canvas extent.
     * @return immutable core commands retaining only scalar native requests.
     */
    fun frame(
        tree: UiTree,
        size: IntSize = IntSize(2, 2),
    ): List<DrawCommand> {
        tree.measure(Constraints.fixed(size.width, size.height))
        tree.layout()
        return tree.paint()
    }

    /**
     * Prepares one actual presentation after the tree's final layout.
     *
     * @param tree fixture-owned tree.
     * @param size logical extent.
     * @param scale positive GUI scale.
     * @return detached immutable presentation, still unqueued.
     */
    fun prepare(
        tree: UiTree,
        size: IntSize = IntSize(2, 2),
        scale: Int = 1,
    ): NativeCanvasPresentation = device.prepare(frame(tree, size), FrameTime(123L), scale)

    /**
     * Queues and consumes a presentation, creating its separate post-consumption completion fence.
     *
     * @param presentation current device-owned batch.
     */
    fun submit(presentation: NativeCanvasPresentation) {
        device.queue(presentation)
        device.consumed()
    }

    override fun close() {
        val failures = CanvasFailures()
        trees.forEach { failures.attempt(it::close) }
        failures.attempt(device::closeAfterGuiDiscarded)
        failures.throwIfPresent()
    }

    /**
     * Deterministic device implementation whose probes never wait or imply one another's completion.
     */
    internal class Driver : NativeCanvasDriver {
        val targets: MutableList<Target> = ArrayList()
        val fences: MutableList<Fence> = ArrayList()
        var allocationAttempts: Int = 0
        var finishCalls: Int = 0
        var drainCalls: Int = 0
        var destroyOnClose: Boolean = true
        var completeDestructionOnDrain: Boolean = true
        var nextAllocationFailure: Throwable? = null
        var nextFenceFailure: Throwable? = null
        var finishFailure: Throwable? = null
        var drainFailure: Throwable? = null
        var onAllocate: (() -> Unit)? = null
        var onDrain: (() -> Unit)? = null

        override fun createTarget(
            physicalSize: IntSize,
            depth: Boolean,
        ): NativeCanvasTarget {
            allocationAttempts += 1
            onAllocate?.invoke()
            val failure = nextAllocationFailure
            nextAllocationFailure = null
            failure?.let { throw it }
            return Target(physicalSize, depth).also {
                it.destroyOnClose = destroyOnClose
                targets += it
            }
        }

        override fun fence(): NativeCanvasFence {
            val failure = nextFenceFailure
            nextFenceFailure = null
            failure?.let { throw it }
            return Fence().also(fences::add)
        }

        override fun finish() {
            finishCalls += 1
            finishFailure?.let { throw it }
        }

        override fun drainRetirements() {
            drainCalls += 1
            onDrain?.invoke()
            drainFailure?.let { throw it }
            if (completeDestructionOnDrain) targets.filter { it.releaseAccepted }.forEach { it.destroyed = true }
        }

        /**
         * Signals every existing fence without changing any resource's close state.
         */
        fun signalAll() {
            fences.forEach { it.signalled = true }
        }
    }

    /**
     * Owned test target that records physical destruction attempts independently of object reachability.
     */
    internal class Target(
        override val size: IntSize,
        val depth: Boolean,
    ) : NativeCanvasTarget {
        var closeCalls: Int = 0
        var closeFailure: Throwable? = null
        var destroyOnClose: Boolean = true
        var destroyed: Boolean = false
        var destructionPolls: Int = 0
        var destructionFailure: Throwable? = null
        var releaseAccepted: Boolean = false
            private set

        override fun close() {
            closeCalls += 1
            check(releaseAccepted.not()) { "A successful target release must not be requested twice." }
            closeFailure?.let { throw it }
            releaseAccepted = true
            if (destroyOnClose) destroyed = true
        }

        override fun isDestroyed(): Boolean {
            check(releaseAccepted) { "Physical destruction may only be queried after a successful release request." }
            destructionPolls += 1
            destructionFailure?.let { throw it }
            return destroyed
        }
    }

    /**
     * Independently controlled completion probe with explicit poll and close failure injection.
     */
    internal class Fence : NativeCanvasFence {
        var signalled: Boolean = false
        var polls: Int = 0
        var closeCalls: Int = 0
        var pollFailure: Throwable? = null
        var closeFailure: Throwable? = null

        override fun isSignalled(): Boolean {
            polls += 1
            pollFailure?.let { throw it }
            return signalled
        }

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }

    /**
     * Per-attachment producer; the externally shared source never becomes this instance's owner.
     */
    internal class Producer : NativeCanvasProducer {
        val captures: MutableList<Capture> = ArrayList()
        var captureCalls: Int = 0
        var closeCalls: Int = 0
        var available: Boolean = true
        var color: Int = 0xFF336699.toInt()
        var snapshotMode: SnapshotMode = SnapshotMode.Matching
        var captureFailure: Throwable? = null
        var renderFailure: Throwable? = null
        var captureCloseFailure: Throwable? = null
        var closeFailure: Throwable? = null
        var onCapture: (() -> Unit)? = null
        var onRender: (() -> Unit)? = null

        override fun capture(): NativeCanvasCapture? {
            captureCalls += 1
            onCapture?.invoke()
            captureFailure?.let { throw it }
            if (available.not()) return null
            return Capture(color, snapshotMode, renderFailure, captureCloseFailure, onRender).also(captures::add)
        }

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }

    /**
     * Typed snapshot policy used to verify exact-receipt capture or explicit absence and rejection.
     */
    internal enum class SnapshotMode {
        /**
         * Exact immutable pixels for the allocated extent.
         */
        Matching,

        /**
         * No CPU snapshot is available for the native generation.
         */
        Missing,

        /**
         * Deliberately invalid snapshot extent.
         */
        WrongExtent,
    }

    /**
     * Immutable-content capture lease retained until its capture fence independently signals.
     */
    internal class Capture(
        private val color: Int,
        private val snapshotMode: SnapshotMode,
        private val renderFailure: Throwable?,
        private val closeFailure: Throwable?,
        private val onRender: (() -> Unit)?,
    ) : NativeCanvasCapture {
        val logicalSizes: MutableList<IntSize> = ArrayList()
        val frameTimes: MutableList<FrameTime> = ArrayList()
        val renderedTargets: MutableList<NativeCanvasTarget> = ArrayList()
        var closeCalls: Int = 0

        override fun render(
            target: NativeCanvasTarget,
            logicalSize: IntSize,
            frameTime: FrameTime,
        ): DrawImage? {
            logicalSizes += logicalSize
            frameTimes += frameTime
            renderedTargets += target
            onRender?.invoke()
            renderFailure?.let { throw it }
            val size =
                when (snapshotMode) {
                    SnapshotMode.Matching -> target.size
                    SnapshotMode.Missing -> return null
                    SnapshotMode.WrongExtent -> IntSize(target.size.width + 1, target.size.height)
                }
            return createDrawImage(size, IntArray(size.width * size.height) { color })
        }

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }
}
