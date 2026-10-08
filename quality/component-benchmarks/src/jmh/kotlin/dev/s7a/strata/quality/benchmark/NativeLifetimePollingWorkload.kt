package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.UiTree
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasCapture
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevice
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDriver
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasFence
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasPresentation
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasProducer
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasTarget
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResource
import dev.s7a.strata.runtime.minecraft.canvas.NativeGuiResourceSet
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Owns actual Canvas/portable records and independently controllable CPU fences for one current trial.
 * Fake completion membership removes closed probes immediately and stays bounded by current issued work.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeLifetimePollingWorkload(
    subject: NativeLifetimePollingBenchmark.Subject,
    private val count: Int,
    private val condition: NativeLifetimePollingBenchmark.Condition,
) : AutoCloseable {
    private val driver = Driver()
    private val device = NativeCanvasDevice(driver)
    private val producers = ArrayList<Producer>()
    private val trees = ArrayList<UiTree>()
    private val sets = ArrayList<NativeGuiResourceSet>()
    private val commands = ArrayList<DrawCommand>()
    private val targetCount = if (subject == NativeLifetimePollingBenchmark.Subject.Portable) 0 else count
    private val portableCount = if (subject == NativeLifetimePollingBenchmark.Subject.Targets) 0 else count
    private var expectedTargets = targetCount
    private var presentation: NativeCanvasPresentation? = null
    private var closed = false

    init {
        require(count in listOf(0, 1, 64))
        repeat(targetCount) {
            val source = device.source({ Producer().also(producers::add) })
            val tree = UiTree()
            trees.add(tree)
            tree.update(evaluateComponentTree { Canvas(source, IntSize(2, 2)) })
            tree.measure(Constraints.fixed(2, 2))
            tree.layout()
            commands.addAll(tree.paint())
        }
        if (targetCount != 0) presentation = device.prepare(commands, FrameTime(1L), 1)
        repeat(portableCount) {
            val set = device.guiResources.reserve(device.guiResources.createOwnerId(), listOf(IntSize(2, 2)))
            sets.add(set)
            val resource = Resource()
            driver.resources.add(resource)
            device.guiResources.add(set, resource)
            device.guiResources.seal(set)
        }
        when (condition) {
            NativeLifetimePollingBenchmark.Condition.InitializationPending -> {
                presentation?.let(device::cancel)
                presentation = null
            }

            NativeLifetimePollingBenchmark.Condition.Settled -> {
                presentation?.let(device::cancel)
                presentation = null
                driver.signalAll()
                device.poll()
            }

            NativeLifetimePollingBenchmark.Condition.GuiPending -> {
                driver.signalAll()
                device.poll()
                queueCurrent()
                device.consumed()
                presentation = null
            }

            NativeLifetimePollingBenchmark.Condition.RetiredPending -> {
                retire()
            }

            NativeLifetimePollingBenchmark.Condition.AsynchronousDestruction -> {
                driver.targets.forEach { it.destroyOnClose = false }
                driver.resources.forEach { it.destroyOnClose = false }
                driver.signalAll()
                device.poll()
                retire()
            }

            NativeLifetimePollingBenchmark.Condition.Quarantined -> {
                driver.signalAll()
                device.poll()
                queueCurrent()
                device.failedGui()
                presentation = null
                retire()
            }

            NativeLifetimePollingBenchmark.Condition.ReloadPending -> {
                presentation?.let(device::cancel)
                presentation = null
                device.reload()
                sets.forEach(device.guiResources::release)
            }
        }
        verifyRetained()
    }

    /**
     * Advances all actual nonblocking managers without changing the selected persistent condition.
     */
    internal fun poll(): Int {
        device.poll()
        return retained()
    }

    /**
     * Enables immediate completions only for the complete submission boundary.
     */
    internal fun immediateCompletions() {
        driver.immediate = true
    }

    /**
     * Submits every current group and settles actual initialization/capture/GUI work without a model lookup.
     */
    internal fun submit(): Int {
        if (targetCount == 1) expectedTargets = 2
        if (targetCount != 0) presentation = device.prepare(commands, FrameTime(2L), 1)
        queueCurrent()
        device.consumed()
        presentation = null
        return retained()
    }

    /**
     * Retires the fresh pending owners, then signals and requires complete physical acknowledgement.
     */
    internal fun retireAndSignal(): Int {
        retire()
        driver.signalAll()
        device.poll()
        check(retained() == 0)
        return retained()
    }

    /**
     * Requires the admitted groups to remain current, including retired asynchronous and quarantined records.
     */
    internal fun verifyRetained() {
        check(device.retainedTargetCount() == expectedTargets)
        check(device.guiResources.retainedSetCount() == portableCount)
        check(driver.targets.size == expectedTargets && driver.resources.size == portableCount)
    }

    private fun retained(): Int = device.retainedTargetCount() + device.guiResources.retainedSetCount()

    private fun queueCurrent() {
        presentation?.let(device::queue)
        sets.forEach { set ->
            device.guiResources.beginUse(set)
            device.guiResources.queued(set)
            device.guiResources.endUse(set)
        }
    }

    private fun retire() {
        presentation?.let(device::cancel)
        presentation = null
        trees.forEach(UiTree::close)
        sets.forEach(device.guiResources::release)
    }

    override fun close() {
        if (closed) return
        closed = true
        trees.forEach(UiTree::close)
        device.closeAfterGuiDiscarded()
        check(retained() == 0 && driver.fences.isEmpty())
        check(driver.targets.all { it.destroyed && it.releaseRequested })
        check(driver.resources.all { it.destroyed && it.releaseRequested })
        check(producers.all { it.closed })
    }

    private class Driver : NativeCanvasDriver {
        val fences = LinkedHashSet<Fence>()
        val targets = ArrayList<Target>()
        val resources = ArrayList<Resource>()
        var immediate = false

        override fun createTarget(
            physicalSize: IntSize,
            depth: Boolean,
        ): NativeCanvasTarget {
            check(depth.not())
            return Target(physicalSize).also(targets::add)
        }

        override fun fence(): NativeCanvasFence = Fence(this, immediate).also(fences::add)

        override fun finish() {
            signalAll()
        }

        override fun drainRetirements() {
            targets.forEach { it.destroyed = true }
            resources.forEach { it.destroyed = true }
        }

        fun signalAll() {
            fences.forEach { it.signalled = true }
        }
    }

    private class Fence(
        private val driver: Driver,
        var signalled: Boolean,
    ) : NativeCanvasFence {
        override fun isSignalled(): Boolean = signalled

        override fun close() {
            check(driver.fences.remove(this))
        }
    }

    private class Resource : NativeGuiResource {
        var releaseRequested = false
        var destroyOnClose = true
        var destroyed = false

        override fun close() {
            check(releaseRequested.not())
            releaseRequested = true
            if (destroyOnClose) destroyed = true
        }

        override fun isDestroyed(): Boolean = destroyed
    }

    private class Target(
        override val size: IntSize,
    ) : NativeCanvasTarget {
        var releaseRequested = false
        var destroyOnClose = true
        var destroyed = false

        override fun close() {
            check(releaseRequested.not())
            releaseRequested = true
            if (destroyOnClose) destroyed = true
        }

        override fun isDestroyed(): Boolean = destroyed
    }

    private class Producer : NativeCanvasProducer {
        private val image = createDrawImage(IntSize(2, 2), IntArray(4) { 0xFF336699.toInt() })
        var closed = false

        override fun capture(): NativeCanvasCapture =
            object : NativeCanvasCapture {
                override fun render(
                    target: NativeCanvasTarget,
                    logicalSize: IntSize,
                    frameTime: FrameTime,
                ): DrawImage {
                    check(target.size == image.size && logicalSize == image.size)
                    return image
                }

                override fun close() = Unit
            }

        override fun close() {
            check(closed.not())
            closed = true
        }
    }
}
