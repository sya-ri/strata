@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.state.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proves that two backends reusing screen and action identities cannot execute each other's queued operations.
 */
internal class RemoteScreenServiceTest {
    @Test
    fun migratingOwnerPreservesNegotiationRetainedStateAndActionDispatch() {
        val owner = RuntimeExecutionOwner()
        val fixture = owner.run { Host() }
        val count = owner.run { mutableStateOf(0) }
        val snapshot = owner.run { fixture.open { count.value += 1 } }
        val packet = owner.run { fixture.action(snapshot) }
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor
                .submit {
                    assertThrows(IllegalStateException::class.java) { fixture.host.tick() }
                    owner.run {
                        fixture.host.enqueue(Unit, packet)
                        fixture.host.tick()
                        assertEquals(1, count.value)
                    }
                }.get(5, TimeUnit.SECONDS)
            owner.run {
                assertEquals(1, count.value)
                fixture.host.tick()
                fixture.close()
            }
        } finally {
            owner.run { fixture.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun aNewBackendIgnoresAnOldOperationWithOtherwiseIdenticalIds() {
        var oldCalls = 0
        var newCalls = 0
        Host().use { old ->
            val oldScreen = old.open { oldCalls++ }
            val stale = old.action(oldScreen)
            Host().use { next ->
                val nextScreen = next.open { newCalls++ }
                assertEquals(oldScreen.session, nextScreen.session)
                assertNotEquals(old.address, next.address)
                next.host.enqueue(Unit, stale)
                next.host.tick()
                assertEquals(0, newCalls)
                val legitimate = next.action(nextScreen)
                next.host.enqueue(Unit, legitimate)
                next.host.tick()
                assertEquals(1, newCalls)
                assertEquals(0, oldCalls)
            }
        }
    }

    @Test
    fun duplicateDiscoveryPreservesTheNegotiatedTransport() {
        Host().use { fixture ->
            val before = fixture.host.capabilities(Unit)
            fixture.host.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            fixture.host.tick()
            assertEquals(before, fixture.host.capabilities(Unit))
            assertEquals(0, fixture.outgoing.size)
        }
    }

    /**
     * Uses the same opaque player identity and built-in schemas as a newly joined backend.
     */
    private class Host : AutoCloseable {
        val outgoing = ArrayDeque<ByteArray>()
        private val incoming = ArrayDeque<ByteArray>()
        val host = RemoteScreenService<Unit, Unit>(RemoteEndpoint.Server, { _, bytes -> outgoing.addLast(bytes) }, { throw it })
        val address: RemoteAddress
        private var sequence = 1L
        private val client = RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types, RemotePacket.limits) { incoming.addLast(RemotePacket.encode(RemotePacket.Frame(address, sequence++, it))) }

        init {
            host.join(Unit)
            host.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            host.tick()
            val greeting = RemotePacket.decode(outgoing.removeFirst()) as RemotePacket.Frame
            address = greeting.address
            client.receive(greeting.bytes, 0)
            client.flush()
            host.enqueue(Unit, incoming.removeFirst())
            host.tick()
        }

        fun open(action: () -> Unit): RemoteMessage.Snapshot {
            host.open(Unit, Unit, ScreenDefinition("Backend") { Spacer(Modifier.Empty.onActivate(action)) })
            host.tick()
            val packet = RemotePacket.decode(outgoing.removeFirst()) as RemotePacket.Frame
            return client.receive(packet.bytes, 0) as RemoteMessage.Snapshot
        }

        fun action(snapshot: RemoteMessage.Snapshot): ByteArray {
            val press =
                snapshot.tree.nodes.values
                    .flatMap { it.modifiers }
                    .single { it.type == BuiltinProjection.PointerPress.type }
            val endpoint = ((press.value as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer).value
            client.send(RemoteMessage.Action(snapshot.session, 1, endpoint, press.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            client.flush()
            return incoming.removeFirst()
        }

        override fun close() {
            host.close()
            client.close()
        }
    }
}
