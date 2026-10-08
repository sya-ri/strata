package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemotePacketStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/** Frozen bridge adapter; only an absent candidate factory chooses the original production connection on the baseline. */
public class OutgoingFragmentConnection {
    private val native: Method? =
        try {
            RemoteConnection.Companion.javaClass.getMethod("native", Set::class.java, RemoteLimits::class.java, RemotePacketStream::class.java)
        } catch (_: NoSuchMethodException) {
            null
        }

    /** Whether this actual loaded runtime supplies the candidate decoder-free native factory. */
    public val hasNativeFactory: Boolean get() = native != null

    /** Calls the actual native factory or the original connection-to-packet-stream binding; linkage failures propagate. */
    public fun create(
        limits: RemoteLimits,
        stream: RemotePacketStream,
    ): RemoteConnection {
        val method = native ?: return RemoteConnection(emptySet(), limits, stream::send)
        return try {
            method.invoke(RemoteConnection.Companion, emptySet<Any>(), limits, stream) as RemoteConnection
        } catch (failure: InvocationTargetException) {
            throw failure.cause ?: failure
        }
    }
}
