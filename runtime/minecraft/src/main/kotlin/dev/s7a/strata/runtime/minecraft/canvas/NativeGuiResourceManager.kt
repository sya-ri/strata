package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * A device-owned GUI cache lifetime participant registered with one [NativeCanvasDevice].
 *
 * Implementations own independently budgeted native resources while borrowing the device's ordered fence, finish, and destruction-drain lifecycle.
 * Every callback runs on the device construction thread, callbacks never reenter the device, and registration transfers terminal cleanup responsibility to the device.
 * Implementations must keep ordinary callbacks nonblocking and may call the device driver's nonblocking fence operation through their constructor-owned driver.
 *
 * This SPI is internal to Strata's versioned adapters and is not a component or application extension point.
 */
@InternalStrataRuntimeApi
public interface NativeGuiResourceManager {
    /**
     * Reports active and retired native entry reservations without polling or allocating.
     */
    public fun retainedResourceCount(): Int

    /**
     * Reports active and retired native payload bytes without polling or allocating.
     */
    public fun retainedResourceBytes(): Long

    /**
     * Settles native resources referenced by GUI work that the version adapter has successfully consumed.
     */
    public fun consumed()

    /**
     * Polls completion and physical destruction without waiting or issuing unconsumed GUI work.
     */
    public fun poll()

    /**
     * Quarantines resources whose queued GUI work failed before complete submission could be established.
     */
    public fun failedGui()

    /**
     * Invalidates every active derived cache entry after a native resource reload.
     */
    public fun reload()

    /**
     * Stops acquisition and drops unconsumed GUI pins before the device performs its sole terminal completion wait.
     */
    public fun beginShutdown()

    /**
     * Closes every owned resource after submitted work completes and before deferred destruction is drained.
     */
    public fun closeAfterFinish()

    /**
     * Requires physical destruction after the device has drained deferred native releases.
     */
    public fun acknowledgeAfterDrain()
}
