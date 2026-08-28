package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.IdentityHashMap

/**
 * Render-thread registry that keeps native canvas cleanup alive independently of visible screens.
 *
 * A version adapter supplies exactly one stable driver identity per physical device.
 * Entries are retained only for that device lifetime and removed after successful terminal cleanup.
 * Every operation runs on the thread that first initializes this registry; callbacks are never dispatched to another thread.
 */
@InternalStrataRuntimeApi
public object NativeCanvasDevices {
    private val ownerThread = Thread.currentThread()
    private val devices = IdentityHashMap<NativeCanvasDriver, NativeCanvasDevice>()
    private var terminal = false

    /**
     * Resolves the lifetime manager for one stable native driver identity without allocating GPU storage.
     *
     * @param driver version-owned singleton for the physical device; using multiple identities for one device violates the capacity contract.
     * @return the current device owner, shared by every screen and source using that driver.
     * @throws IllegalStateException when called off the registry's owner thread or after terminal shutdown begins.
     */
    public fun device(driver: NativeCanvasDriver): NativeCanvasDevice {
        requireOwner()
        check(terminal.not()) { "Native canvas devices cannot be acquired after terminal shutdown begins." }
        return devices.getOrPut(driver) { NativeCanvasDevice(driver) }
    }

    /**
     * Reports active, retired, asynchronously releasing, and failed target-set permits across all registered devices.
     *
     * Each physical device still enforces its own 64-set limit; this diagnostic sum does not combine their allocation budgets.
     * It observes owner-thread accounting without allocating, polling, or retaining native resources and returns zero for an empty registry.
     *
     * @return the total permits still owned by registered devices until acknowledged physical destruction.
     * @throws IllegalStateException when called off the registry's owner thread.
     * @throws ArithmeticException when the aggregate cannot be represented as an Int.
     */
    public fun retainedTargetCount(): Int {
        requireOwner()
        return devices.values.fold(0) { count, device -> Math.addExact(count, device.retainedTargetCount()) }
    }

    /**
     * Reports separately budgeted portable GUI resource sets across every registered native device.
     *
     * The sum includes active, retired, partially initialized, quarantined, and asynchronously destroying sets.
     * It neither polls nor invokes a native API, is safe after terminal shutdown, and does not change Canvas target accounting.
     *
     * @return the current portable-set permit count, or zero when all registered devices have released their resources.
     * @throws IllegalStateException off the registry owner thread.
     * @throws ArithmeticException if the sum cannot be represented as an Int.
     */
    public fun retainedGuiResourceSetCount(): Int {
        requireOwner()
        return devices.values.fold(0) { count, device -> Math.addExact(count, device.guiResources.retainedSetCount()) }
    }

    /**
     * Polls all device fences without waiting, including frames without a visible Strata screen.
     *
     * @throws Throwable after every device has been attempted, preserving the first cleanup failure.
     */
    public fun poll() {
        requireOwner()
        val failures = CanvasFailures()
        devices.values.toList().forEach { device -> failures.attempt { device.poll() } }
        failures.throwIfPresent()
    }

    /**
     * Records native GUI consumption and then polls all device lifetimes on the render thread.
     *
     * The version adapter calls this after flushing or submitting GUI work, including consumer failure paths.
     * It must never be called between extraction and consumption.
     *
     * @throws Throwable after every independent device cleanup has been attempted.
     */
    public fun consumed() {
        requireOwner()
        val failures = CanvasFailures()
        devices.values.toList().forEach { device -> failures.attempt { device.consumed() } }
        failures.throwIfPresent()
    }

    /**
     * Invalidates device-owned derived presentation generations after a resource reload.
     *
     * External sources remain owned by their callers and old GPU resources retire without waiting.
     *
     * @throws Throwable after all registered devices have been invalidated and cleanup attempted.
     */
    public fun reload() {
        requireOwner()
        val failures = CanvasFailures()
        devices.values.toList().forEach { device -> failures.attempt { device.reload() } }
        failures.throwIfPresent()
    }

    /**
     * Quarantines outstanding GUI generations after an incomplete native consumer operation.
     *
     * Every device is attempted on the render thread; targets stay retained until terminal queue discard and device completion.
     *
     * @throws Throwable after every device has been attempted, preserving the first cleanup failure.
     */
    public fun failedGui() {
        requireOwner()
        val failures = CanvasFailures()
        devices.values.toList().forEach { device -> failures.attempt { device.failedGui() } }
        failures.throwIfPresent()
    }

    /**
     * Closes every device after native GUI queues have been discarded and before native device destruction.
     *
     * Acquisition becomes permanently unavailable before any close callback runs, including reentrant callbacks.
     * Successful entries are removed; failed entries remain quarantined rather than falsely returning lifetime permits.
     * This is the sole registry operation that may wait for already submitted GPU work.
     *
     * @throws Throwable after attempting every device, preserving the first failure and suppressing later failures.
     */
    public fun closeAfterGuiDiscarded() {
        requireOwner()
        terminal = true
        val failures = CanvasFailures()
        devices.entries.map { it.key to it.value }.forEach { (driver, device) ->
            failures.attempt {
                device.closeAfterGuiDiscarded()
                devices.remove(driver)
            }
        }
        failures.throwIfPresent()
    }

    private fun requireOwner() {
        check(Thread.currentThread() === ownerThread) { "Native canvas registry operations require the render owner thread." }
    }
}
