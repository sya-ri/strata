package dev.s7a.strata.runtime.velocity

import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteMessageCodec
import dev.s7a.strata.runtime.remote.RemotePacket
import java.nio.ByteBuffer

/**
 * Immutable literal native inputs independent of production packet encoding/framing.
 * The unchanged message codec supplies logical application bytes; header/framing offsets, tags and boundaries are literal.
 * Source and complete reference creation precede every isolated callback/owner sample.
 */
internal object NativeRoutingInputs {
    /**
     * Complete bounded native sequence for one fixed actor incarnation and selected routing/control row.
     */
    fun packets(
        index: Int,
        workload: NativeRoutingWorkload,
        direction: NativeRoutingDirection,
    ): List<ByteArray> {
        val endpoint = if (direction == NativeRoutingDirection.ClientProxy || workload == NativeRoutingWorkload.ProxyImpersonation) RemoteEndpoint.Proxy else RemoteEndpoint.Server
        return when (workload) {
            NativeRoutingWorkload.Discovery -> {
                listOf(byteArrayOf(0))
            }

            NativeRoutingWorkload.Malformed -> {
                listOf(byteArrayOf())
            }

            NativeRoutingWorkload.UnknownKind -> {
                listOf(byteArrayOf(2))
            }

            NativeRoutingWorkload.UnknownEndpoint -> {
                listOf(frame(index, endpoint, 2, ByteArray(17)).also { it[1] = 2 })
            }

            NativeRoutingWorkload.Cancellation -> {
                listOf(
                    frame(
                        index,
                        endpoint,
                        2,
                        ByteBuffer
                            .allocate(17)
                            .putLong(2)
                            .putInt(0)
                            .putInt(0)
                            .put(0)
                            .array(),
                    ),
                )
            }

            else -> {
                fragment(index, endpoint, logical(workload))
            }
        }
    }

    private fun logical(workload: NativeRoutingWorkload): ByteArray {
        val codec = RemoteMessageCodec()

        fun action(size: Int): RemoteMessage = RemoteMessage.Action(1, 1, 1, BuiltinProjection.PointerPress.type, ProjectionValue.Bytes(ByteArray(size) { (it * 31 + 7).toByte() }))

        fun sized(size: Int): ByteArray = codec.encode(action(size - codec.encode(action(0)).size)).also { check(it.size == size) }
        return when (workload) {
            NativeRoutingWorkload.MinimumOpaque -> byteArrayOf(127)
            NativeRoutingWorkload.MaximumOpaque -> ByteArray(24534) { 127 }
            NativeRoutingWorkload.Greeting -> codec.encode(RemoteMessage.Hello(1, RemotePacket.limits, emptySet()))
            NativeRoutingWorkload.MultiFragment -> sized(24535)
            NativeRoutingWorkload.OneMiB -> sized(1048576)
            NativeRoutingWorkload.NearLimit -> sized(RemotePacket.limits.messageBytes - 1)
            else -> codec.encode(RemoteMessage.Resynchronize(1))
        }
    }

    private fun fragment(
        index: Int,
        endpoint: RemoteEndpoint,
        logical: ByteArray,
    ): List<ByteArray> =
        (0 until (logical.size - 1) / 24534 + 1).map { part ->
            val offset = part * 24534
            val count = minOf(24534, logical.size - offset)
            val inner =
                ByteBuffer
                    .allocate(16 + count)
                    .putLong(2)
                    .putInt(logical.size)
                    .putInt(offset)
                    .put(logical, offset, count)
                    .array()
            frame(index, endpoint, part + 2L, inner)
        }

    /**
     * One fixed big-endian literal envelope; no public native encoder or decoder is reused by the reference.
     */
    private fun frame(
        index: Int,
        endpoint: RemoteEndpoint,
        sequence: Long,
        inner: ByteArray,
    ): ByteArray =
        ByteBuffer
            .allocate(26 + inner.size)
            .put(1.toByte())
            .put(endpoint.ordinal.toByte())
            .putLong(0)
            .putLong(index + 1L)
            .putLong(sequence)
            .put(inner)
            .array()
}
