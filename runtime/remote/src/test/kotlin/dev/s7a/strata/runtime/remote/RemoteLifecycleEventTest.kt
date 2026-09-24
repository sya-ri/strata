@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSessionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real negotiated messages prove event timing, ownership, reentrant controls, and terminal ordering.
 */
internal class RemoteLifecycleEventTest {
    @Test
    fun acknowledgementsEmitOnlyAppliedChangesAndDisconnectClosesEveryOwnerFirst() {
        Host().use { fixture ->
            val owner = Any()
            val other = Any()
            val category = UiCategory(ResourceId("example", "map"))
            val first = fixture.service.open(owner, Unit, UiDefinition(presentation = UiPresentation.Hud, category = category) { Spacer() })
            val second = fixture.service.open(other, Unit, UiDefinition(presentation = UiPresentation.Hud) { Spacer() })
            assertNull(first.uiSession.presentation)
            assertEquals(1, fixture.events.size)
            val snapshots = fixture.drain().filterIsInstance<RemoteMessage.Snapshot>()
            snapshots.forEach { fixture.send(RemoteMessage.ControlApplied(it.session, checkNotNull(it.control).sequence)) }
            snapshots.forEach { fixture.send(RemoteMessage.ControlApplied(it.session, checkNotNull(it.control).sequence)) }
            assertEquals(2, fixture.events.filterIsInstance<RemoteLifecycleEvent.Opened<*>>().size)
            val opened = fixture.events.filterIsInstance<RemoteLifecycleEvent.Opened<*>>().first()
            assertSame(owner, opened.owner)
            assertSame(first.uiSession, opened.session)
            assertEquals(category, opened.category)

            first.uiSession.switch(UiPresentation.Screen)
            first.uiSession.switch(UiPresentation.Hud)
            val superseded = fixture.drain().filterIsInstance<RemoteMessage.Control>()
            superseded.forEach { fixture.send(RemoteMessage.ControlApplied(first.identity, it.state.sequence)) }
            assertTrue(fixture.events.none { it is RemoteLifecycleEvent.PresentationChanged<*> })
            first.uiSession.switch(UiPresentation.Screen)
            val applied = fixture.drain().filterIsInstance<RemoteMessage.Control>().single()
            fixture.send(RemoteMessage.ControlApplied(first.identity, applied.state.sequence))
            fixture.send(RemoteMessage.ControlApplied(first.identity, applied.state.sequence))
            val change = fixture.events.filterIsInstance<RemoteLifecycleEvent.PresentationChanged<*>>().single()
            assertEquals(UiPresentation.Hud, change.previous)
            assertEquals(UiPresentation.Screen, change.presentation)
            assertSame(opened.session, change.session)
            fixture.send(RemoteMessage.Resynchronize(first.identity))
            fixture.drain().filterIsInstance<RemoteMessage.Snapshot>().forEach { fixture.send(RemoteMessage.ControlApplied(it.session, checkNotNull(it.control).sequence)) }
            assertEquals(1, fixture.events.filterIsInstance<RemoteLifecycleEvent.PresentationChanged<*>>().size)

            fixture.service.disconnect(Unit)
            fixture.service.disconnect(Unit)
            fixture.send(RemoteMessage.ControlApplied(first.identity, applied.state.sequence))
            val closed = fixture.events.filterIsInstance<RemoteLifecycleEvent.Closed<*>>()
            assertEquals(setOf(first.identity, second.identity), closed.map { it.identity }.toSet())
            assertTrue(closed.all { it.reason == UiCloseReason.Disconnected && it.session.status is UiSessionStatus.Closed })
            assertEquals(1, fixture.events.filterIsInstance<RemoteLifecycleEvent.Disconnected>().size)
            assertTrue(fixture.events.last() is RemoteLifecycleEvent.Disconnected)
        }
    }

    @Test
    fun listenersCanCloseAndReplaceWithoutRevivingTheOldSession() {
        Host().use { fixture ->
            val owner = Any()
            var replacement: RemoteScreenSession? = null
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Opened) {
                    event.session.close()
                } else if (event is RemoteLifecycleEvent.Closed && replacement == null) {
                    replacement = fixture.service.open(owner, Unit, UiDefinition(presentation = UiPresentation.Hud) { Spacer() })
                }
            }
            val first = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
            val initial = fixture.drain().filterIsInstance<RemoteMessage.Snapshot>().single()
            fixture.send(RemoteMessage.ControlApplied(first.identity, checkNotNull(initial.control).sequence))
            assertTrue(first.uiSession.status is UiSessionStatus.Closed)
            assertEquals(1, fixture.events.filterIsInstance<RemoteLifecycleEvent.Opened<*>>().size)
            assertEquals(1, fixture.events.filterIsInstance<RemoteLifecycleEvent.Closed<*>>().size)
            assertEquals(RemoteSessionStatus.Open, checkNotNull(replacement).status)
            fixture.listener = {}
        }
    }

    @Test
    fun recursivelyOpeningFromTerminalNotificationsHasABoundedEventBudget() {
        val failures = mutableListOf<Throwable>()
        var notifications = 0
        lateinit var service: RemoteScreenService<Unit, Any>
        service =
            RemoteScreenService(RemoteEndpoint.Server, { _, _ -> }, failures::add, notify = { _, event ->
                if (event is RemoteLifecycleEvent.Closed) {
                    notifications++
                    service.open(Any(), Unit, UiDefinition { Spacer() })
                }
            })
        service.use {
            val original = service.open(Any(), Unit, UiDefinition { Spacer() })
            assertTrue(original.uiSession.status is UiSessionStatus.Closed)
            assertEquals(65, notifications)
            assertEquals(RemoteFailure.ResourceLimit, (failures.single() as RemoteProtocolException).reason)
        }
    }

    @Test
    fun unavailableOpeningAndListenerFailureDoNotInventReadiness() {
        val failures = mutableListOf<Throwable>()
        val events = mutableListOf<RemoteLifecycleEvent<Any>>()
        RemoteScreenService<Unit, Any>(RemoteEndpoint.Server, { _, _ -> }, failures::add, notify = { _, event ->
            events += event
            error("Listener failed")
        }).use { service ->
            val handle = service.open(Any(), Unit, UiDefinition { Spacer() })
            assertTrue(handle.uiSession.status is UiSessionStatus.Closed)
            assertEquals(UiCloseReason.Unsupported, (events.single() as RemoteLifecycleEvent.Closed).reason)
            assertEquals(1, failures.size)
            handle.close()
            service.disconnect(Unit)
            assertEquals(1, events.size)
        }
    }

    /**
     * Authenticated packet route with explicit native-application acknowledgements.
     */
    private class Host : AutoCloseable {
        val events = mutableListOf<RemoteLifecycleEvent<Any>>()
        var listener: (RemoteLifecycleEvent<Any>) -> Unit = {}
        private val outgoing = ArrayDeque<ByteArray>()
        private val incoming = ArrayDeque<ByteArray>()
        val service =
            RemoteScreenService<Unit, Any>(RemoteEndpoint.Server, { _, bytes -> outgoing.addLast(bytes) }, { throw it }, notify = { _, event ->
                events += event
                listener(event)
            })
        private val address: RemoteAddress
        private var sequence = 1L
        private val client =
            RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types, RemotePacket.limits) {
                incoming.addLast(RemotePacket.encode(RemotePacket.Frame(address, sequence++, it)))
            }

        init {
            service.join(Unit)
            service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            service.tick()
            val greeting = RemotePacket.decode(outgoing.removeFirst()) as RemotePacket.Frame
            address = greeting.address
            client.receive(greeting.bytes, 0)
            client.flush()
            while (incoming.isNotEmpty()) service.enqueue(Unit, incoming.removeFirst())
            service.tick()
        }

        fun drain(): List<RemoteMessage> {
            service.tick()
            return buildList {
                while (outgoing.isNotEmpty()) {
                    val packet = RemotePacket.decode(outgoing.removeFirst()) as RemotePacket.Frame
                    client.receive(packet.bytes, 0)?.let(::add)
                }
            }
        }

        fun send(message: RemoteMessage) {
            client.send(message)
            client.flush()
            while (incoming.isNotEmpty()) service.enqueue(Unit, incoming.removeFirst())
            service.tick()
        }

        override fun close() {
            listener = {}
            service.close()
            client.close()
        }
    }
}
