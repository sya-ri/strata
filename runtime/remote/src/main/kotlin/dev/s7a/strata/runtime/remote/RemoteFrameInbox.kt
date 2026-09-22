package dev.s7a.strata.runtime.remote

/**
 * Bounded ingress between network callbacks and the connection's owner thread.
 * Exhaustion permanently fails the inbox and clears its bytes; the owner must terminate the connection.
 * Queue entries own defensive byte snapshots, bounded by both byte count and entry count.
 */
public class RemoteFrameInbox(
    private val limits: RemoteLimits = RemoteLimits(),
) : AutoCloseable {
    private val lock = Any()
    private val frames = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var exceeded = false

    public val failed: Boolean get() = synchronized(lock) { exceeded }

    /**
     * Enqueues a frame from any thread, returning false after close or a terminal bound violation.
     */
    public fun offer(frame: ByteArray): Boolean =
        synchronized(lock) {
            if (closed) return@synchronized false
            val invalidSize = frame.size <= 16 || limits.frameBytes < frame.size
            if (invalidSize || limits.pendingBytes - pendingBytes < frame.size || limits.collectionEntries <= frames.size) {
                exceeded = true
                release()
                return@synchronized false
            }
            frames.addLast(frame.copyOf())
            pendingBytes += frame.size
            true
        }

    /**
     * Transfers one queued snapshot to the owner, or returns null when no frame is pending.
     */
    public fun poll(): ByteArray? =
        synchronized(lock) {
            if (frames.isEmpty()) return@synchronized null
            frames.removeFirst().also { pendingBytes -= it.size }
        }

    override fun close() {
        synchronized(lock) { release() }
    }

    private fun release() {
        closed = true
        frames.clear()
        pendingBytes = 0
    }
}
