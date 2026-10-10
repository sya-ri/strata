package dev.s7a.strata.runtime.remote

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import java.nio.ByteBuffer

/**
 * Actual public and connection-owned decoding routes for shared consumer correctness tests.
 * Each connection negotiates the message's complete component/modifier schemas and closes on every exit.
 */
internal enum class RemoteDecodeRoute {
    Public,
    Connection,
    ;

    /**
     * Returns a fully validated detached message through the selected real ingress boundary.
     */
    fun decode(message: RemoteMessage): RemoteMessage {
        val codec = RemoteMessageCodec()
        val bytes = codec.encode(message)
        if (this == Public) return codec.decode(bytes)
        val types = types(message)
        return RemoteConnection(types, send = {}).use { connection ->
            connection.start()
            connection.flush()
            RemoteFraming(RemotePacket.limits).use { sender ->
                sender.send(codec.encode(RemoteMessage.Hello(RemoteConnection.PROTOCOL_VERSION, RemoteLimits(), types))) { check(connection.receive(it, 0) == null) }
            }
            var complete: RemoteMessage? = null
            RemoteFraming().use { sender ->
                sender.send(bytes) { frame ->
                    ByteBuffer.wrap(frame).putLong(0, 2)
                    val result = connection.receive(frame, 0)
                    if (result != null) {
                        check(complete == null)
                        complete = result
                    }
                }
            }
            checkNotNull(complete)
        }
    }

    /**
     * Carries a binding or resource projection inside a complete typed message before returning its detached value.
     */
    fun value(
        type: ProjectionType,
        value: ProjectionValue,
    ): ProjectionValue = (decode(RemoteMessage.Action(1, 1, 1, type, value)) as RemoteMessage.Action).value

    private fun types(message: RemoteMessage): Set<ProjectionType> =
        when (message) {
            is RemoteMessage.Action -> {
                setOf(message.type)
            }

            is RemoteMessage.Snapshot -> {
                message.tree.nodes.values
                    .flatMap { listOf(it.declaration.type) + it.modifiers.map { modifier -> modifier.type } }
                    .toSet()
            }

            is RemoteMessage.Update -> {
                message.patch.changed
                    .flatMap { listOf(it.declaration.type) + it.modifiers.map { modifier -> modifier.type } }
                    .toSet()
            }

            else -> {
                emptySet()
            }
        }
}
