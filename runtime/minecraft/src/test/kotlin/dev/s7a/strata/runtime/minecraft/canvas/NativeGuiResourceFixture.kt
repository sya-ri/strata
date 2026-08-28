package dev.s7a.strata.runtime.minecraft.canvas

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Exercises portable resource accounting on the same deterministic device used by native Canvas protocol tests.
 *
 * Every operation belongs to the constructing test thread, and close releases all owned trees and device resources.
 * Native work is simulated by independent completion probes; this fixture does not claim loaded GPU verification.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class NativeGuiResourceFixture : AutoCloseable {
    val canvas: NativeCanvasFixture = NativeCanvasFixture()
    val device: NativeCanvasDevice = canvas.device
    val driver: NativeCanvasFixture.Driver = canvas.driver
    val gui: NativeGuiResources = device.guiResources
    val owner: NativeGuiResourceOwnerId = gui.createOwnerId()
    val allocations: MutableList<Resource> = ArrayList()

    init {
        driver.onDrain = {
            if (driver.completeDestructionOnDrain && driver.drainFailure == null) {
                allocations.filter { it.releaseAccepted }.forEach { it.destroyed = true }
            }
        }
    }

    /**
     * Allocates one deterministic layer and immediately transfers it to an already reserved generation.
     *
     * @param set unsealed set with a remaining reserved layer.
     * @return fixture-observed resource; only the device may close it after successful transfer.
     * @throws IllegalStateException when the transfer violates the owner protocol.
     */
    fun add(set: NativeGuiResourceSet): Resource =
        Resource().also { resource ->
            allocations += resource
            gui.add(set, resource)
        }

    /**
     * Reserves and initializes a complete generation without signalling its initialization fence.
     *
     * @param count exact layer count, including zero for an empty generation.
     * @param ownerId stable presenter identity sharing the three-generation budget.
     * @return owned sealed token, valid until retirement acknowledges every layer's physical destruction.
     * @throws Throwable when reservation, initialization fencing, or eligible cleanup fails.
     */
    fun initialized(
        count: Int = 1,
        ownerId: NativeGuiResourceOwnerId = owner,
    ): NativeGuiResourceSet {
        val set = gui.reserve(ownerId, List(count) { IntSize(2, 2) })
        repeat(count) { add(set) }
        gui.seal(set)
        return set
    }

    override fun close() {
        canvas.close()
    }

    /**
     * Independently controlled resource release and physical acknowledgement with deterministic failure injection.
     *
     * It belongs exclusively to the device after add and rejects repeated successful close requests.
     * Test callbacks run synchronously on the owner thread and may inspect or retire other resources.
     */
    internal class Resource : NativeGuiResource {
        var closeCalls: Int = 0
        var closeFailure: Throwable? = null
        var onClose: (() -> Unit)? = null
        var destroyOnClose: Boolean = true
        var destroyed: Boolean = false
        var destructionPolls: Int = 0
        var destructionFailure: Throwable? = null
        var releaseAccepted: Boolean = false
            private set

        override fun close() {
            closeCalls += 1
            check(releaseAccepted.not()) { "A successful portable resource release must not be requested twice." }
            onClose?.invoke()
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
}
