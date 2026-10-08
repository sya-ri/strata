@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.ExecutionOwnerId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import java.nio.ByteBuffer

/**
 * Private final-envelope storage for one logical outgoing transfer, confined to its constructing execution owner.
 * Factories allocate and copy directly into fresh storage; no externally aliased completed frame can be adopted.
 * The native header stays reserved until actual ordered delivery, and taken arrays are never reused or mutated later.
 * Frame sizes and queue bytes exclude reserved headroom; reserved array payload is envelopeBytes times retained frames.
 * JVM headers/alignment and group metadata remain additional measured overhead.
 * Terminal release clears all unsent storage and the owner identity without retaining a logical source buffer.
 */
@InternalStrataRuntimeApi
internal class RemoteNativeTransfer private constructor(
    private val frames: ArrayDeque<ByteArray>,
) : AutoCloseable {
    private var owner: ExecutionOwnerId? = RuntimeExecutionOwner.current()

    /** Current retained fragment count; empty groups retain no payload or owner. */
    val frameCount: Int
        get() {
            checkReadableOwner()
            return frames.size
        }

    /** Semantic bytes charged by existing inner-fragment admission, excluding native headroom. */
    val queuedBytes: Int
        get() {
            checkReadableOwner()
            return frames.sumOf { it.size - RemotePacket.envelopeBytes }
        }

    /** Inner length of the next privately held fragment, without exposing its array. */
    val firstBytes: Int
        get() {
            checkOwner()
            return frames.first().size - RemotePacket.envelopeBytes
        }

    /** Checked reserved-array-payload bytes, excluding JVM headers/alignment, released as fragments leave the queue. */
    val retainedHeadroom: Long get() = Math.multiplyExact(RemotePacket.envelopeBytes.toLong(), frameCount.toLong())

    /**
     * Removes one exclusive final-envelope array before a native writer or header/sequence check can fail.
     * Only the packet stream uses this bridge; no caller can put an aliased public frame back into the group.
     */
    fun takeFirst(): ByteArray {
        checkOwner()
        return frames.removeFirst().also { if (frames.isEmpty()) owner = null }
    }

    /**
     * Creates an unsent cancellation with the same inner identity, reading after the reserved native header.
     * The connection replaces and closes this group while preserving FIFO; no outer sequence is assigned here.
     */
    fun cancellation(): RemoteNativeTransfer {
        checkOwner()
        val source = frames.first()
        val identity = ByteBuffer.wrap(source, RemotePacket.envelopeBytes, source.size - RemotePacket.envelopeBytes).long
        val frame = ByteArray(Math.addExact(RemotePacket.envelopeBytes, 17))
        ByteBuffer.wrap(frame).position(RemotePacket.envelopeBytes).putLong(identity).putInt(0).putInt(0).put(0)
        return RemoteNativeTransfer(ArrayDeque(listOf(frame)))
    }

    override fun close() {
        if (owner == null) return
        checkOwner()
        frames.clear()
        owner = null
    }

    private fun checkReadableOwner() {
        if (owner != null) checkOwner()
    }

    private fun checkOwner() {
        check(owner != null && RuntimeExecutionOwner.current() == owner) { "The native transfer is released or belongs to another execution owner." }
    }

    /**
     * Fresh storage construction used only by the retained framing owner.
     */
    companion object {
        /**
         * Copies logical bytes once into envelope-sized fragments with the original inner framing layout.
         * [admit] applies unchanged connection byte/entry accounting after each fragment allocation, before publishing it.
         * Failed partial construction releases the private prefix; the guarded connection resets its admission counters.
         */
        fun create(
            bytes: ByteArray,
            identity: Long,
            limits: RemoteLimits,
            admit: (Int) -> Unit,
        ): RemoteNativeTransfer {
            require(bytes.isNotEmpty() && bytes.size <= limits.messageBytes) { "Invalid logical message length." }
            require(identity in 1 until Long.MAX_VALUE) { "Invalid logical message identity." }
            val frames = ArrayDeque<ByteArray>()
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val count = minOf(limits.frameBytes - 16, bytes.size - offset)
                    val innerBytes = Math.addExact(16, count)
                    val frame = ByteArray(Math.addExact(RemotePacket.envelopeBytes, innerBytes))
                    ByteBuffer.wrap(frame)
                        .position(RemotePacket.envelopeBytes)
                        .putLong(identity)
                        .putInt(bytes.size)
                        .putInt(offset)
                        .put(bytes, offset, count)
                    admit(innerBytes)
                    frames.addLast(frame)
                    offset += count
                }
                return RemoteNativeTransfer(frames)
            } catch (failure: Throwable) {
                frames.clear()
                throw failure
            }
        }
    }
}
