@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.ExecutionOwnerId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner

/**
 * One-shot, execution-owner-confined handoff of a newly decoded native packet.
 * Only [decode] can create this handle; its fragment is detached from the input envelope and is never exposed to the producer.
 * After matching execution-owner checks, [RemotePacketStream.offer] consumes frame storage before queue validation.
 * [close] releases unselected packets.
 * Routing metadata remains readable after consumption; payload and owner references do not.
 */
@InternalStrataRuntimeApi
public class RemotePacketAdmission private constructor(
    decoded: RemotePacket,
) : AutoCloseable {
    private var owner: ExecutionOwnerId? = RuntimeExecutionOwner.current()
    private var packet: RemotePacket? = decoded

    /**
     * Decoded native envelope kind, independent of payload ownership or admission outcome.
     */
    public val kind: Kind =
        when (decoded) {
            RemotePacket.Discovery -> Kind.Discovery
            is RemotePacket.Frame -> Kind.Frame
        }

    /**
     * Immutable routing identity for a frame, or null for discovery.
     */
    public val address: RemoteAddress? = (decoded as? RemotePacket.Frame)?.address

    /**
     * Transfers the exclusively held frame once, clearing this handle before queue validation or callbacks can fail.
     * The receiving stream must check its execution owner before calling this method.
     */
    internal fun takeFrame(): RemotePacket.Frame {
        checkOwner()
        val frame = packet as? RemotePacket.Frame ?: error("The admission handle has no available frame.")
        packet = null
        owner = null
        return frame
    }

    override fun close() {
        if (owner == null) return
        checkOwner()
        packet = null
        owner = null
    }

    private fun checkOwner() {
        check(owner != null && RuntimeExecutionOwner.current() == owner) { "The admission handle is consumed, closed or belongs to another execution owner." }
    }

    /**
     * Native tag decoded before routing; a frame carries private storage until one transfer or close.
     */
    public enum class Kind {
        Discovery,
        Frame,
    }

    /**
     * Privileged decoder boundary, with no factory accepting an externally aliased public Frame.
     */
    public companion object {
        /**
         * Validates and detaches through [RemotePacket.decode] before returning an opaque owner-confined handle.
         * Malformed envelopes preserve the public decoder's failure behavior and cannot publish partial storage.
         */
        public fun decode(bytes: ByteArray): RemotePacketAdmission = RemotePacketAdmission(RemotePacket.decode(bytes))
    }
}
