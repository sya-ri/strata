package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Slot
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * A failing error reporter must not interrupt cleanup of other selected sessions.
 */
internal class RemoteServiceShutdownTest {
    @ParameterizedTest
    @EnumSource(Shutdown::class)
    fun reporterFailureDoesNotInterruptOtherSessionCleanup(shutdown: Shutdown) {
        val listenerFailure = IllegalStateException("Listener failed.")
        val reporterFailure = IllegalStateException("Reporter failed.")
        val outgoing = List(2) { ArrayDeque<ByteArray>() }
        var closedEvents = 0
        var failNotifications = true
        val service =
            RemoteScreenService<Int, Unit>(
                RemoteEndpoint.Server,
                { player, bytes -> outgoing[player].addLast(bytes) },
                { throw reporterFailure },
                notify = { _, event ->
                    if (failNotifications && event is RemoteLifecycleEvent.Closed) {
                        closedEvents += 1
                        throw listenerFailure
                    }
                },
            )
        val clients = mutableListOf<RemoteConnection>()
        try {
            outgoing.indices.forEach { player ->
                service.join(player)
                service.enqueue(player, RemotePacket.encode(RemotePacket.Discovery))
                service.tick()
                val greeting = RemotePacket.decode(outgoing[player].removeFirst()) as RemotePacket.Frame
                var sequence = 1L
                val client =
                    RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types, RemotePacket.limits) { bytes ->
                        service.enqueue(player, RemotePacket.encode(RemotePacket.Frame(greeting.address, sequence++, bytes)))
                    }
                clients += client
                client.receive(greeting.bytes, 0)
                client.flush()
                service.tick()
            }
            val handles =
                if (shutdown == Shutdown.Container) {
                    List(2) { service.open(Unit, 0, UiDefinition(presentation = UiPresentation.Hud) { Slot() }) }
                } else {
                    outgoing.indices.map { player -> service.open(Unit, player, UiDefinition { Spacer() }) }
                }
            val terminate = {
                when (shutdown) {
                    Shutdown.Service -> service.close()
                    Shutdown.Owner -> service.ownerDisabled(Unit)
                    Shutdown.Container -> service.containerChanged(0)
                }
            }
            assertSame(reporterFailure, assertThrows(IllegalStateException::class.java, terminate))
            assertEquals(2, closedEvents)
            handles.forEach { handle -> assertEquals(RemoteSessionStatus.Closed(shutdown.reason), handle.status) }
            terminate()
            assertEquals(2, closedEvents)
        } finally {
            failNotifications = false
            service.close()
            clients.forEach(RemoteConnection::close)
        }
    }

    /**
     * Independent retirement boundaries and their existing terminal status reasons.
     */
    enum class Shutdown(
        val reason: RemoteFailure,
    ) {
        Service(RemoteFailure.OwnerClosed),
        Owner(RemoteFailure.OwnerDisabled),
        Container(RemoteFailure.ContainerChanged),
    }
}
