package dev.s7a.strata.runtime.remote

import java.nio.ByteBuffer

/**
 * Ordered bounded framing for one player connection.
 * Only one partially received logical message is retained; interleaving or replay is rejected.
 * A zero-length cancellation frame retires that transfer without delivering partial application data.
 * The owner supplies monotonic milliseconds and closes this object on disconnect or protocol failure.
 */
public class RemoteFraming(
    private val limits: RemoteLimits = RemoteLimits(),
) : AutoCloseable {
    private var nextOutgoing: Long = 1
    private var lastIncoming: Long = 0
    private var receiving: Long = 0
    private var pending: ByteArray? = null
    private var received: Int = 0
    private var startedAt: Long = 0
    private var closed: Boolean = false

    /**
     * Delivers consecutive fragments synchronously; the caller must not interleave another message.
     */
    public fun send(
        bytes: ByteArray,
        send: (ByteArray) -> Unit,
    ) {
        check(closed.not()) { "Remote framing is closed." }
        require(bytes.isNotEmpty() && bytes.size <= limits.messageBytes) { "Invalid logical message length." }
        check(nextOutgoing < Long.MAX_VALUE) { "Remote transport identity space is exhausted." }
        val identity = nextOutgoing++
        var offset = 0
        while (offset < bytes.size) {
            val count = minOf(limits.frameBytes - HEADER_BYTES, bytes.size - offset)
            val frame = ByteBuffer.allocate(HEADER_BYTES + count)
            frame
                .putLong(identity)
                .putInt(bytes.size)
                .putInt(offset)
                .put(bytes, offset, count)
            send(frame.array())
            offset += count
        }
    }

    /**
     * Returns a completed owned message, or null while receiving its remaining fragments.
     * Any invalid fragment releases partial data before its failure escapes.
     */
    public fun receive(
        bytes: ByteArray,
        nowMillis: Long,
    ): ByteArray? {
        check(closed.not()) { "Remote framing is closed." }
        return try {
            expire(nowMillis)
            require(bytes.size in (HEADER_BYTES + 1)..limits.frameBytes) { "Invalid remote frame length." }
            val frame = ByteBuffer.wrap(bytes)
            val identity = frame.long
            val total = frame.int
            val offset = frame.int
            if (total == 0) {
                require(offset == 0 && frame.remaining() == 1 && frame.get() == 0.toByte()) { "Invalid remote cancellation frame." }
                require(lastIncoming < identity && (pending == null || receiving == identity)) { "Cancellation targets another transfer." }
                lastIncoming = identity
                clearPending()
                return null
            }
            require(total in 1..limits.messageBytes) { "Invalid logical message length." }
            if (pending == null) {
                require(lastIncoming < identity && offset == 0) { "Stale or out-of-order remote message." }
                receiving = identity
                pending = ByteArray(total)
                startedAt = nowMillis
            }
            val target = checkNotNull(pending)
            require(receiving == identity && target.size == total && offset == received) { "Interleaved or out-of-order remote fragment." }
            require(frame.remaining() <= target.size - received) { "Remote fragment exceeds its declared message." }
            val count = frame.remaining()
            frame.get(target, received, count)
            received += count
            if (received == target.size) {
                lastIncoming = receiving
                clearPending()
                target
            } else {
                null
            }
        } catch (failure: IllegalArgumentException) {
            clearPending()
            throw failure
        }
    }

    /**
     * Checks an incomplete transfer even when the peer stops sending bytes.
     */
    public fun expire(nowMillis: Long) {
        check(closed.not()) { "Remote framing is closed." }
        if (pending != null && (nowMillis < startedAt || limits.assemblyMillis <= nowMillis - startedAt)) {
            clearPending()
            throw IllegalArgumentException("Remote message assembly timed out.")
        }
    }

    /**
     * Replaces the unsent remainder of a started transfer with an explicit receiver-side release operation.
     * The transport owner passes a frame from that same transfer and preserves queue order.
     */
    internal fun cancel(frame: ByteArray): ByteArray {
        require(HEADER_BYTES < frame.size)
        val identity = ByteBuffer.wrap(frame).long
        return ByteBuffer
            .allocate(HEADER_BYTES + 1)
            .putLong(identity)
            .putInt(0)
            .putInt(0)
            .put(0)
            .array()
    }

    /**
     * Releases partially assembled bytes and prevents subsequent use.
     */
    override fun close() {
        closed = true
        clearPending()
    }

    private fun clearPending() {
        pending = null
        receiving = 0
        received = 0
        startedAt = 0
    }

    private companion object {
        const val HEADER_BYTES: Int = 16
    }
}
