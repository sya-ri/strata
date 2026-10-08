package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * One frozen reflection adapter invokes the actual decoder and stream in either runtime archive.
 * Only absence of the privileged class selects the baseline; incomplete linkage or invocation failures propagate.
 * Public Frame input can never select the privileged overload.
 */
internal class IncomingFragmentDecoder(
    route: IncomingFragmentRoute,
) {
    private val admission =
        if (route == IncomingFragmentRoute.Production) {
            try {
                Class.forName("dev.s7a.strata.runtime.remote.RemotePacketAdmission", false, RemotePacket::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                null
            }
        } else {
            null
        }
    private val companion = admission?.getField("Companion")?.get(null) ?: RemotePacket.Companion
    private val decode = companion.javaClass.getMethod("decode", ByteArray::class.java)
    private val offer = RemotePacketStream::class.java.getMethod("offer", admission ?: RemotePacket.Frame::class.java, Long::class.javaPrimitiveType)
    private val address = (admission ?: RemotePacket.Frame::class.java).getMethod("getAddress")

    /**
     * Validates one native packet and transfers or snapshots its fragment without retaining the result.
     * Discovery is decoded and released but never passed to the frame-only queue.
     */
    fun receive(
        stream: RemotePacketStream,
        native: ByteArray,
    ) {
        val packet = checkNotNull(invoke(decode, companion, native))
        if (admission == null) {
            if (packet is RemotePacket.Frame) {
                invoke(address, packet)
                invoke(offer, stream, packet, 0L)
            }
        } else {
            (packet as AutoCloseable).use {
                if (invoke(address, packet) != null) invoke(offer, stream, packet, 0L)
            }
        }
    }

    /**
     * Reports actual decoder-storage identity and queue-copy work before timing, rather than predicting JIT allocation.
     * Rejected frames contribute decoder bytes but no queue arrays on either revision.
     */
    fun inspect(
        stream: RemotePacketStream,
        native: ByteArray,
    ): Pair<Int, Int> {
        val packet = checkNotNull(invoke(decode, companion, native))
        val frame =
            if (admission == null) {
                packet as? RemotePacket.Frame
            } else {
                admission.getDeclaredField("packet").apply { isAccessible = true }.get(packet) as? RemotePacket.Frame
            }
        val pending = RemotePacketStream::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(stream) as Map<*, *>
        val previous = frame?.let { pending[it.sequence] }
        try {
            if (frame != null) invoke(offer, stream, packet, 0L)
            val stored = frame?.let { pending[it.sequence] }
            val admitted = previous == null && stored != null
            val copied = if (frame != null && admitted && stored !== frame.bytes) frame.bytes.size else 0
            return (frame?.bytes?.size ?: 0) to copied
        } finally {
            if (admission != null) (packet as AutoCloseable).close()
        }
    }

    private fun invoke(
        method: Method,
        target: Any,
        vararg arguments: Any,
    ): Any? =
        try {
            method.invoke(target, *arguments)
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
}
