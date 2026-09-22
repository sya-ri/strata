@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.evaluateComponentTree
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.runtime.spi.createRuntimeUiSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises real DSL declaration, projection, reconstruction, input, and reactive server updates over encoded messages.
 */
internal class RemoteSessionTest {
    @Test
    fun realInputInvokesServerHandlerOnceAndUpdatesTheExistingClientHost() {
        val clicks = mutableStateOf(0)
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val codec = RemoteMessageCodec()
        val outbound = mutableListOf<RemoteMessage>()
        val incoming = mutableListOf<RemoteMessage>()
        val server =
            RemoteServerSession(1, ProjectionValue.Text("test"), registry.types, send = { outbound.add(codec.decode(codec.encode(it))) }) {
                evaluateComponentTree {
                    val color = if (clicks.value == 0) ArgbColor(-65536) else ArgbColor(-16776961)
                    Spacer(modifier = Modifier.Empty.background(color).onActivate { clicks.value += 1 })
                }
            }
        server.use {
            server.tick()
            val client = RemoteClientSession(outbound.removeAt(0) as RemoteMessage.Snapshot, registry, send = { incoming.add(codec.decode(codec.encode(it))) })
            client.use {
                val content = client.definition(UiText.Literal("test")).transfer().content
                createRuntimeUiSession { evaluateComponentTree(content) }.use { host ->
                    host.attach()
                    val first = host.frame(Constraints.fixed(8, 8))
                    host.dispatchPointer(PointerEvent.Press(IntOffset(2, 2), PointerButton.Primary))
                    val action = incoming.filterIsInstance<RemoteMessage.Action>().single()
                    assertEquals(0, clicks.value)
                    server.receive(action)
                    server.receive(action)
                    assertEquals(1, clicks.value)
                    server.tick()
                    deliver(outbound, client)
                    val updated = host.frame(Constraints.fixed(8, 8))
                    assertNotEquals(first.drawCommands, updated.drawCommands)
                    assertEquals(localFrame().drawCommands, updated.drawCommands)
                }
            }
        }
        assertTrue(server.status is RemoteSessionStatus.Closed)
    }

    private fun deliver(
        messages: List<RemoteMessage>,
        client: RemoteClientSession,
    ) {
        messages.forEach { message ->
            when (message) {
                is RemoteMessage.Update -> client.receive(message)
                is RemoteMessage.Acknowledgement -> client.receive(message)
                else -> error("Unexpected message.")
            }
        }
    }

    private fun localFrame(): RuntimeUiFrame =
        createRuntimeUiSession {
            evaluateComponentTree { Spacer(modifier = Modifier.Empty.background(ArgbColor(-16776961))) }
        }.use { local ->
            local.attach()
            local.frame(Constraints.fixed(8, 8))
        }

    @Test
    fun olderAcknowledgementDoesNotOverwriteNewerDraftButNewGenerationDoes() {
        val edit = RemoteEditBuffer("initial")
        edit.edit("first", 1)
        edit.edit("second", 2)
        assertEquals(false, edit.reconcile("first", 1, 1))
        assertEquals("second", edit.value)
        assertEquals(true, edit.reconcile("reset", 2, 0))
        assertEquals("reset", edit.value)
        assertEquals(false, edit.reconcile("second", 1, 2))
        assertEquals("reset", edit.value)
    }
}
