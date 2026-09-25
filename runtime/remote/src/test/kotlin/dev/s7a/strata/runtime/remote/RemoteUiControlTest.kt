@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.spi.RuntimeUiControl
import dev.s7a.strata.runtime.spi.RuntimeUiController
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiInputPolicy
import dev.s7a.strata.ui.UiInteractionMode
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Server ordering and client intent acknowledgements without native rendering or networking.
 */
internal class RemoteUiControlTest {
    private val registry = RemoteRegistry().also(RemoteBuiltins::register)

    @Test
    fun nativeInteractionExitReachesItsServerOnceAndCannotReviveAfterClose() {
        val outbound = mutableListOf<RemoteMessage>()
        val incoming = mutableListOf<RemoteMessage>()
        server(outbound).use { server ->
            server.uiSession.switch(UiPresentation.Hud)
            server.uiSession.setInteractionMode(UiInteractionMode.Cursor)
            server.tick()
            val snapshot = outbound.single() as RemoteMessage.Snapshot
            val state = checkNotNull(snapshot.control)
            lateinit var native: RuntimeUiController
            native = RuntimeUiController(UiPresentation.Hud, apply = { native.applied(it.sequence) }, close = {})
            native.start()
            native.setInteractionMode(UiInteractionMode.Cursor)
            RemoteClientSession(snapshot, registry, send = incoming::add).use { client ->
                client.bindUiSession(native)
                client.controlApplied(state, null)
                server.receive(RemoteMessage.ControlApplied(1, state.sequence))
                incoming.clear()
                native.setInteractionMode(UiInteractionMode.None)
                repeat(2) { client.synchronizeInteraction() }
                val request = incoming.single() as RemoteMessage.ControlRequest
                assertEquals(UiInteractionMode.None, request.state.interactionMode)
                server.receive(request)
                val applied = outbound.filterIsInstance<RemoteMessage.Control>().last().state
                client.controlApplied(applied, null)
                server.receive(RemoteMessage.ControlApplied(1, applied.sequence))
                assertEquals(UiInteractionMode.None, server.uiSession.interactionMode)
                native.close()
                client.synchronizeInteraction()
                assertEquals(1, incoming.size)
            }
        }
    }

    @Test
    fun serverWaitsForLatestNativeApplicationAndResynchronizationKeepsItsSequence() {
        val outbound = mutableListOf<RemoteMessage>()
        server(outbound).use { server ->
            server.tick()
            val initial = checkNotNull((outbound.single() as RemoteMessage.Snapshot).control)
            assertNull(server.uiSession.presentation)
            server.receive(RemoteMessage.ControlApplied(1, initial.sequence))
            assertEquals(UiPresentation.Screen, server.uiSession.presentation)
            server.uiSession.switch(UiPresentation.Hud)
            val obsolete = (outbound.last() as RemoteMessage.Control).state
            server.uiSession.switch(UiPresentation.Screen)
            val latest = (outbound.last() as RemoteMessage.Control).state
            server.receive(RemoteMessage.ControlApplied(1, obsolete.sequence))
            assertEquals(UiSessionStatus.Switching(UiPresentation.Screen), server.uiSession.status)
            server.resynchronize()
            assertEquals(latest, (outbound.last() as RemoteMessage.Snapshot).control)
            server.receive(RemoteMessage.ControlApplied(1, latest.sequence))
            assertEquals(UiSessionStatus.Ready(), server.uiSession.status)
            server.uiSession.switch(UiPresentation.Hud)
            val rejected = (outbound.last() as RemoteMessage.Control).state
            server.receive(RemoteMessage.ControlApplied(1, rejected.sequence, UiRejection.Capacity))
            assertEquals(UiPresentation.Screen, server.uiSession.presentation)
            assertEquals(UiSessionStatus.Ready(UiRejection.Capacity), server.uiSession.status)
            server.uiSession.close()
            assertEquals(UiPresentation.Screen, server.uiSession.presentation)
        }
    }

    @Test
    fun olderApplicationAndReceiptsCannotCompleteANewerClientIntent() {
        val outbound = mutableListOf<RemoteMessage>()
        val incoming = mutableListOf<RemoteMessage>()
        server(outbound).use { server ->
            server.tick()
            val snapshot = outbound.single() as RemoteMessage.Snapshot
            RemoteClientSession(snapshot, registry, send = incoming::add).use { client ->
                val initial = checkNotNull(snapshot.control)
                client.controlApplied(initial, null)
                client.uiSession.switch(UiPresentation.Hud)
                val first = incoming.last() as RemoteMessage.ControlRequest
                client.uiSession.switch(UiPresentation.Screen)
                val second = incoming.last() as RemoteMessage.ControlRequest
                val hud = RuntimeUiControl(2, UiPresentation.Hud, UiInputPolicy.BlockAll, UiInteractionMode.None)
                client.controlApplied(hud, null)
                client.receive(RemoteMessage.ControlReceipt(1, first.state.sequence))
                assertEquals(UiSessionStatus.Switching(UiPresentation.Screen), client.uiSession.status)
                client.controlApplied(hud.copy(sequence = 3, presentation = UiPresentation.Screen), UiRejection.ScreenOpen)
                client.receive(RemoteMessage.ControlReceipt(1, second.state.sequence))
                assertEquals(UiPresentation.Hud, client.uiSession.presentation)
                assertEquals(UiSessionStatus.Ready(UiRejection.ScreenOpen), client.uiSession.status)
                client.controlApplied(initial, null)
                assertEquals(UiPresentation.Hud, client.uiSession.presentation)
            }
        }
    }

    @Test
    fun duplicateAndNoOpClientRequestsReceiveConfirmationWithoutAnotherNativeControl() {
        val outbound = mutableListOf<RemoteMessage>()
        server(outbound).use { server ->
            server.tick()
            val state = checkNotNull((outbound.single() as RemoteMessage.Snapshot).control)
            server.receive(RemoteMessage.ControlApplied(1, state.sequence))
            outbound.clear()
            val request = RemoteMessage.ControlRequest(1, state.copy(sequence = 1))
            repeat(2) { server.receive(request) }
            assertEquals(2, outbound.size)
            outbound.forEach { assertInstanceOf(RemoteMessage.ControlReceipt::class.java, it) }
        }
    }

    private fun server(outbound: MutableList<RemoteMessage>): RemoteServerSession =
        RemoteServerSession(1, ProjectionValue.Text("test"), registry.types, send = outbound::add) {
            evaluateComponentTree { Spacer() }
        }
}
