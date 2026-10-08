@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner

/**
 * Protocol negotiation and ordered transport for one authenticated connection, confined to its execution owner.
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
    private var nativeStream: RemotePacketStream? = null
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
     * Flushes at most [maxFrames] queued frames, stopping at the first empty queue.
     * Bounds transport pressure per adapter tick and releases the write guard after success or failure.
     */
    public fun flush(maxFrames: Int = 8) {
        checkOwner()
        require(0 < maxFrames) { "The frame budget must be positive." }
        check(sending.not()) { "Remote transport writes cannot reenter." }
        val transport = checkNotNull(outgoing) { "Remote connection is closed." }
        sending = true
        try {
            guarded {
                for (index in 0 until maxFrames) {
                    val transfer = pending.firstOrNull() ?: break
                    val bytes = transfer.firstBytes
                    transfer.started = true
                    if (transfer.frameCount == 1) pending.removeFirst()
                    queuedBytes -= bytes
                    queuedFrames--
                    transfer.sendNext(transport, nativeStream)
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
                    retained.addLast(transfer.cancellation(framing))
                }
                transfer.clear()
            }
        }
        pending.clear()
        pending.addAll(retained)
        queuedFrames = pending.sumOf { it.frameCount }
        queuedBytes = pending.sumOf { it.queuedBytes }
    }

    override fun close() {
        checkOwner()
        outgoing = null
        capabilities = null
        nativeStream = null
        pending.forEach(Transfer::clear)
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
        val encoded = codec.encode(message)
        if (nativeStream == null) {
            val frames = ArrayDeque<ByteArray>()
            framing.send(encoded) { frame ->
                admit(frame.size, activeLimits)
                frames.addLast(frame)
            }
            pending.addLast(Transfer.Public(message.session, frames))
        } else {
            val frames = framing.nativeTransfer(encoded) { bytes -> admit(bytes, activeLimits) }
            pending.addLast(Transfer.Native(message.session, frames))
        }
    }

    /**
     * Charges the existing inner-fragment byte/entry budgets before publishing a new transfer.
     * Native physical headroom is bounded separately and never tightens ordinary pending admission.
     */
    private fun admit(
        bytes: Int,
        activeLimits: RemoteLimits,
    ) {
        if (activeLimits.pendingBytes - queuedBytes < bytes || activeLimits.collectionEntries <= queuedFrames) {
            throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Remote send queue is full.")
        }
        queuedBytes += bytes
        queuedFrames++
    }

    /**
     * Current native headroom only, bounded by the admitted fragment count and released on every terminal path.
     */
    internal val retainedNativeHeadroom: Long
        get() {
            checkOwner()
            return if (nativeStream == null) 0 else Math.multiplyExact(RemotePacket.envelopeBytes.toLong(), queuedFrames.toLong())
        }

    /**
     * One logical ordered message retaining either ordinary public callback frames or private final envelopes.
     * The connection removes completed groups and debits counters before calling either native/public writer.
     */
    private sealed class Transfer(
        val session: Long?,
    ) {
        var started: Boolean = false
        abstract val firstBytes: Int
        abstract val frameCount: Int
        abstract val queuedBytes: Int

        abstract fun sendNext(
            send: (ByteArray) -> Unit,
            stream: RemotePacketStream?,
        )

        abstract fun cancellation(framing: RemoteFraming): Transfer

        abstract fun clear()

        /**
         * Ordinary callback storage, retaining the original inner-only layout and array ownership.
         */
        class Public(
            session: Long?,
            private val frames: ArrayDeque<ByteArray>,
        ) : Transfer(session) {
            override val firstBytes: Int get() = frames.first().size
            override val frameCount: Int get() = frames.size
            override val queuedBytes: Int get() = frames.sumOf { it.size }

            override fun sendNext(
                send: (ByteArray) -> Unit,
                stream: RemotePacketStream?,
            ) {
                send(frames.removeFirst())
            }

            override fun cancellation(framing: RemoteFraming): Transfer = Public(null, ArrayDeque(listOf(framing.cancel(frames.first()))))

            override fun clear() {
                frames.clear()
            }
        }

        /**
         * Exclusive final-envelope group; header completion and outer sequence assignment happen only during sendNext.
         */
        class Native(
            session: Long?,
            private val frames: RemoteNativeTransfer,
        ) : Transfer(session) {
            override val firstBytes: Int get() = frames.firstBytes
            override val frameCount: Int get() = frames.frameCount
            override val queuedBytes: Int get() = frames.queuedBytes

            override fun sendNext(
                send: (ByteArray) -> Unit,
                stream: RemotePacketStream?,
            ) {
                checkNotNull(stream).sendNative(frames)
            }

            override fun cancellation(framing: RemoteFraming): Transfer = Native(null, frames.cancellation())

            override fun clear() {
                frames.close()
            }
        }
    }

    private inline fun <T> guarded(block: () -> T): T =
        runCatching(block).getOrElse { failure ->
            close()
            throw failure
        }

    private fun checkOwner() {
        check(RuntimeExecutionOwner.current() == owner) { "Remote connection belongs to another execution owner." }
    }

    /**
     * Stable channel and wire-version identifiers shared by platform adapters.
     */
    public companion object {
        /**
         * Constructs the opt-in private native envelope path under the packet stream's execution owner.
         * Public constructors still deliver detached inner fragments; callers separately own and close [stream].
         * Fresh final-envelope storage is allocated at framing and completed once at actual bounded flush.
         */
        @InternalStrataRuntimeApi
        public fun native(
            types: Set<ProjectionType>,
            limits: RemoteLimits = RemotePacket.limits,
            stream: RemotePacketStream,
        ): RemoteConnection {
            stream.checkExecutionOwner()
            return RemoteConnection(types, limits, stream::send).also { it.nativeStream = stream }
        }

        public const val PROTOCOL_VERSION: Int = 1
        public const val CHANNEL: String = "strata:ui"
    }
}
