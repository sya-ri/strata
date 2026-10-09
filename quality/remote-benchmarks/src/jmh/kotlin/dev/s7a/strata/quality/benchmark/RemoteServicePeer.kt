@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemotePacketStream
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Real client protocol owner, with current validated trees and bounded outgoing packet queues.
 * Address identity comes from discovery; no fixture-generated address bypasses authentication.
 */
internal class RemoteServicePeer(
    val player: Int,
    limits: RemoteLimits = RemotePacket.limits,
) : AutoCloseable {
    val outbound = ArrayDeque<ByteArray>()
    val inbound = ArrayDeque<ByteArray>()
    val messages = ArrayDeque<RemoteMessage>()
    val trees = linkedMapOf<Long, RemoteTree>()
    val revisions = linkedMapOf<Long, Long>()
    private var stream: RemotePacketStream? = null
    val connection = RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types, limits) { bytes -> checkNotNull(stream).send(bytes) }
    val address: RemoteAddress? get() = stream?.address
    var packets: Long = 0
        private set
    var bytes: Long = 0
        private set
    var inspect: ((RemoteMessage) -> Unit)? = null

    /**
     * Consumes one phase's actual packets and validates every decoded current tree in order.
     */
    fun deliver() {
        while (outbound.isNotEmpty()) {
            val bytes = outbound.removeFirst()
            val packet = RemotePacket.decode(bytes) as RemotePacket.Frame
            val current = stream ?: RemotePacketStream(packet.address) { inbound.addLast(it) }.also { stream = it }
            check(packet.address == current.address)
            packets++
            this.bytes += bytes.size
            current.offer(packet, 0)
        }
        stream?.drain(0) { bytes ->
            connection.receive(bytes, 0)?.let { message ->
                inspect?.invoke(message)
                when (message) {
                    is RemoteMessage.Snapshot -> {
                        trees[message.session] = message.tree
                        revisions[message.session] = message.revision
                    }

                    is RemoteMessage.Update -> {
                        check(revisions[message.session] == message.baseRevision)
                        trees[message.session] = message.patch.apply(checkNotNull(trees[message.session]))
                        revisions[message.session] = message.revision
                    }

                    is RemoteMessage.Close -> {
                        trees.remove(message.session)
                        revisions.remove(message.session)
                    }

                    else -> {}
                }
                messages.addLast(message)
            }
        }
        connection.capabilities?.let { stream?.limitTo(it.limits) }
        check(outbound.isEmpty())
    }

    /**
     * Sends only acknowledgements for the actual decoded revision/control generation.
     */
    fun acknowledge() {
        while (messages.isNotEmpty()) {
            when (val message = messages.removeFirst()) {
                is RemoteMessage.Snapshot -> {
                    connection.send(RemoteMessage.Applied(message.session, message.revision))
                    message.control?.let { connection.send(RemoteMessage.ControlApplied(message.session, it.sequence)) }
                }

                is RemoteMessage.Update -> {
                    connection.send(RemoteMessage.Applied(message.session, message.revision))
                }

                is RemoteMessage.Control -> {
                    connection.send(RemoteMessage.ControlApplied(message.session, message.state.sequence))
                }

                else -> {}
            }
        }
        connection.flush()
    }

    override fun close() {
        connection.close()
        stream?.close()
        stream = null
        outbound.clear()
        inbound.clear()
        messages.clear()
        trees.clear()
        revisions.clear()
        inspect = null
    }
}
