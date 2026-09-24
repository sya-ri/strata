@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner

/**
 * Owner-thread protocol negotiation and ordered transport for one authenticated player connection.
 * The adapter supplies authenticated bytes and synchronous transport writes, never arbitrary peers.
 * The fixed bootstrap limits apply until both greetings have been sent; thereafter both sides use their intersection.
 * All partial buffers and the transport callback are released on close or protocol failure.
 */
public class RemoteConnection(
    types: Set<ProjectionType>,
    private val limits: RemoteLimits = RemoteLimits(),
    send: (ByteArray) -> Unit,
) : AutoCloseable {
    private val owner = RuntimeExecutionOwner.current()
    private val supported = types.toSet()
    private var outgoing: ((ByteArray) -> Unit)? = send
    private var framing = RemoteFraming(RemotePacket.limits)
    private var codec = RemoteMessageCodec(RemoteLimits(reconstructionMillis = limits.reconstructionMillis))
    private var greeted = false
    private var sending = false
    private val pending = ArrayDeque<Transfer>()
    private var queuedBytes = 0
    private var queuedFrames = 0
    private var firstTickMillis: Long? = null

    public var capabilities: RemoteCapabilities? = null
        private set

    /**
     * Sends the one bootstrap greeting once the underlying play connection can carry custom payloads.
     */
    public fun start() {
        checkOwner()
        check(greeted.not()) { "Remote greeting was already sent." }
        greeted = true
        guarded { write(RemoteMessage.Hello(PROTOCOL_VERSION, limits, supported)) }
    }

    /**
     * Accepts one frame and returns only complete post-negotiation application messages.
     * The first peer greeting is consumed here; a repeated greeting or premature application message is rejected.
     */
    public fun receive(
        bytes: ByteArray,
        nowMillis: Long,
    ): RemoteMessage? {
        checkOwner()
        check(outgoing != null) { "Remote connection is closed." }
        return guarded {
            val assembled = framing.receive(bytes, nowMillis) ?: return@guarded null
            val message = codec.decode(assembled)
            if (message is RemoteMessage.Hello) {
                negotiate(message)
                null
            } else {
                require(capabilities != null) { "Remote application data arrived before negotiation." }
                message
            }
        }
    }

    /**
     * Sends one application message after successful capability negotiation.
     * Queue exhaustion fails the connection explicitly; actions are never dropped silently.
     */
    public fun send(message: RemoteMessage) {
        checkOwner()
        check(capabilities != null) { "Remote negotiation is incomplete." }
        require((message is RemoteMessage.Hello).not()) { "Use start to send a greeting." }
        if (message is RemoteMessage.Close) discardSession(message.session)
        guarded { write(message) }
    }

    /**
     * Applies assembly and negotiation deadlines even when the peer sends no more fragments.
     */
    public fun tick(nowMillis: Long) {
        checkOwner()
        guarded {
            framing.expire(nowMillis)
            val started = firstTickMillis ?: nowMillis.also { firstTickMillis = it }
            if (capabilities == null && limits.assemblyMillis < nowMillis - started) {
                throw RemoteProtocolException(RemoteFailure.TimedOut, "Remote negotiation exceeded its deadline.")
            }
        }
    }

    /**
     * Flushes at most [maxFrames] queued frames, bounding work and transport pressure per adapter tick.
     */
    public fun flush(maxFrames: Int = 8) {
        checkOwner()
        require(0 < maxFrames) { "The frame budget must be positive." }
        check(sending.not()) { "Remote transport writes cannot reenter." }
        val transport = checkNotNull(outgoing) { "Remote connection is closed." }
        sending = true
        try {
            guarded {
                repeat(maxFrames) {
                    val transfer = pending.firstOrNull() ?: return@repeat
                    val frame = transfer.frames.removeFirst()
                    transfer.started = true
                    if (transfer.frames.isEmpty()) pending.removeFirst()
                    queuedBytes -= frame.size
                    queuedFrames--
                    transport(frame)
                }
            }
        } finally {
            sending = false
        }
    }

    /**
     * Releases unsent work for a terminal session, retaining only ordered cancellation for an already started fragment group.
     * Callers must separately send its typed Close notification; active business actions are never coalesced.
     */
    public fun discardSession(identity: Long) {
        checkOwner()
        check(sending.not()) { "Remote transport writes cannot reenter." }
        val retained = ArrayDeque<Transfer>()
        pending.forEach { transfer ->
            if (transfer.session != identity) {
                retained.addLast(transfer)
            } else {
                if (transfer.started) {
                    val cancellation = framing.cancel(transfer.frames.first())
                    retained.addLast(Transfer(null, ArrayDeque(listOf(cancellation))))
                }
                transfer.frames.clear()
            }
        }
        pending.clear()
        pending.addAll(retained)
        queuedFrames = pending.sumOf { it.frames.size }
        queuedBytes = pending.sumOf { transfer -> transfer.frames.sumOf { it.size } }
    }

    override fun close() {
        checkOwner()
        outgoing = null
        capabilities = null
        pending.clear()
        queuedBytes = 0
        queuedFrames = 0
        firstTickMillis = null
        framing.close()
    }

    private fun negotiate(hello: RemoteMessage.Hello) {
        require(capabilities == null) { "Remote capabilities cannot change during a connection." }
        if (hello.protocol != PROTOCOL_VERSION) {
            throw RemoteProtocolException(RemoteFailure.UnsupportedProtocol, "Unsupported Strata protocol ${hello.protocol}.")
        }
        if (greeted.not()) start()
        val negotiated = limits.intersect(hello.limits)
        framing.close()
        framing = RemoteFraming(negotiated)
        codec = RemoteMessageCodec(negotiated)
        capabilities = RemoteCapabilities(negotiated, supported.intersect(hello.types))
    }

    private fun write(message: RemoteMessage) {
        check(sending.not()) { "Remote transport writes cannot reenter." }
        check(outgoing != null) { "Remote connection is closed." }
        val activeLimits = capabilities?.limits ?: limits
        val frames = ArrayDeque<ByteArray>()
        framing.send(codec.encode(message)) { frame ->
            if (activeLimits.pendingBytes - queuedBytes < frame.size || activeLimits.collectionEntries <= queuedFrames) {
                throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Remote send queue is full.")
            }
            frames.addLast(frame)
            queuedBytes += frame.size
            queuedFrames++
        }
        pending.addLast(Transfer(message.session, frames))
    }

    /**
     * One logical ordered message whose terminal owner can release its remaining encoded frames.
     */
    private class Transfer(
        val session: Long?,
        val frames: ArrayDeque<ByteArray>,
        var started: Boolean = false,
    )

    private inline fun <T> guarded(block: () -> T): T =
        runCatching(block).getOrElse { failure ->
            close()
            throw failure
        }

    private fun checkOwner() {
        check(RuntimeExecutionOwner.current() === owner) { "Remote connection belongs to another execution owner." }
    }

    /**
     * Stable channel and wire-version identifiers shared by platform adapters.
     */
    public companion object {
        public const val PROTOCOL_VERSION: Int = 1
        public const val CHANNEL: String = "strata:ui"
    }
}
