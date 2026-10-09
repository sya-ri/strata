package dev.s7a.strata.runtime.remote

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Bounded native-channel envelope shared by direct servers, proxies, and clients.
 * Discovery requests greetings from both hosts; subsequent frames target one exact host incarnation.
 * The fixed envelope is included in the native channel bound and excluded from the negotiated inner fragment bound.
 */
public sealed interface RemotePacket {
    /**
     * Sent after native channel registration, and forwarded by a proxy when the backend changes.
     * Repeated discovery does not reset a live host connection.
     */
    public data object Discovery : RemotePacket

    /**
     * One owned inner protocol fragment associated with its authenticated host incarnation.
     * Callers must not modify [bytes] after passing this packet to another owner.
     */
    public class Frame(
        public val address: RemoteAddress,
        public val sequence: Long,
        public val bytes: ByteArray,
    ) : RemotePacket

    /**
     * Strict binary envelope codec; native adapters retain their existing channel and packet codecs.
     */
    public companion object {
        private const val HEADER_BYTES: Int = 26
        private val nativeLimit = RemoteLimits().frameBytes

        /**
         * Reserved storage for the opt-in private outgoing path; excluded from negotiated inner bytes.
         */
        @InternalStrataRuntimeApi
        internal val envelopeBytes: Int get() = HEADER_BYTES

        /**
         * Completes a privately allocated final envelope immediately before actual native delivery.
         * Public encode continues allocating detached storage; this bridge accepts only runtime-private transfer output.
         */
        @InternalStrataRuntimeApi
        internal fun completeNative(
            address: RemoteAddress,
            sequence: Long,
            bytes: ByteArray,
        ): ByteArray {
            require(bytes.size in (HEADER_BYTES + 17)..nativeLimit) { "Invalid routed fragment length." }
            require(0 < sequence) { "Invalid routed fragment sequence." }
            writeHeader(ByteBuffer.wrap(bytes), address, sequence)
            return bytes
        }

        /**
         * Fragment limits leaving room for the native-channel envelope.
         */
        public val limits: RemoteLimits = RemoteLimits(frameBytes = nativeLimit - HEADER_BYTES)

        /**
         * Copies a packet into bounded native-channel bytes.
         */
        public fun encode(packet: RemotePacket): ByteArray =
            when (packet) {
                Discovery -> {
                    byteArrayOf(Kind.Discovery.ordinal.toByte())
                }

                is Frame -> {
                    require(packet.bytes.size in 17..limits.frameBytes) { "Invalid routed fragment length." }
                    require(0 < packet.sequence) { "Invalid routed fragment sequence." }
                    writeHeader(ByteBuffer.allocate(HEADER_BYTES + packet.bytes.size), packet.address, packet.sequence)
                        .put(packet.bytes)
                        .array()
                }
            }

        /**
         * Validates length and tags before allocating the owned fragment.
         */
        public fun decode(bytes: ByteArray): RemotePacket {
            require(bytes.size in 1..nativeLimit) { "Invalid native remote packet length." }
            val buffer = ByteBuffer.wrap(bytes)
            val kind = Kind.entries.getOrNull(buffer.get().toInt()) ?: throw IllegalArgumentException("Unknown remote packet kind.")
            return when (kind) {
                Kind.Discovery -> {
                    require(buffer.hasRemaining().not()) { "Trailing discovery data." }
                    Discovery
                }

                Kind.Frame -> {
                    require(HEADER_BYTES + 17 <= bytes.size) { "Truncated routed fragment." }
                    val endpoint = RemoteEndpoint.entries.getOrNull(buffer.get().toInt()) ?: throw IllegalArgumentException("Unknown remote endpoint.")
                    val address = RemoteAddress(endpoint, UUID(buffer.long, buffer.long))
                    val sequence = buffer.long
                    require(0 < sequence) { "Invalid routed fragment sequence." }
                    Frame(address, sequence, ByteArray(buffer.remaining()).also(buffer::get))
                }
            }
        }

        private fun writeHeader(
            buffer: ByteBuffer,
            address: RemoteAddress,
            sequence: Long,
        ): ByteBuffer =
            buffer
                .put(Kind.Frame.ordinal.toByte())
                .put(address.endpoint.ordinal.toByte())
                .putLong(address.incarnation.mostSignificantBits)
                .putLong(address.incarnation.leastSignificantBits)
                .putLong(sequence)

        private enum class Kind { Discovery, Frame }
    }
}
