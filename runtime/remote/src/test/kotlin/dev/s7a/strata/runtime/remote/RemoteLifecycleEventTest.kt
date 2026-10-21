@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.remote

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.projection.ProjectionType
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

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
    fun rejectedOwnerWithdrawalPreservesSchemasUntilAnAdmittedRetry() {
        val owner = Any()
        val other = Any()
        val extension = ProjectionType(ResourceId("example", "withdrawal"), 1)
        lateinit var service: RemoteScreenService<Unit, Any>
        service =
            RemoteScreenService(RemoteEndpoint.Server, { _, _ -> }, { throw it }, notify = { _, event ->
                if (event is RemoteLifecycleEvent.Closed) {
                    repeat(64) { service.ownerDisabled(other) }
                    val failure = assertThrows(RemoteProtocolException::class.java) { service.ownerDisabled(owner) }
                    assertEquals(RemoteFailure.ResourceLimit, failure.reason)
                }
            })
        service.use {
            service.register(owner, extension)
            service.open(Any(), Unit, UiDefinition { Spacer() })
            assertThrows(IllegalArgumentException::class.java) { service.register(other, extension) }
            service.ownerDisabled(owner)
            service.register(other, extension)
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

    @Test
    fun serviceCloseRejectsAdmissionBeforeTerminalListenersRun() {
        Host().use { fixture ->
            val owner = Any()
            val definition = UiDefinition { Spacer() }
            val extension = ProjectionType(ResourceId("example", "terminal"), 1)
            val handle = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
            fixture.drain()
            var terminalEvents = 0
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Closed || event is RemoteLifecycleEvent.Disconnected) {
                    terminalEvents++
                    assertThrows(IllegalStateException::class.java) { fixture.service.join(Unit) }
                    assertThrows(IllegalStateException::class.java) { fixture.service.register(owner, extension) }
                    assertThrows(IllegalStateException::class.java) { fixture.service.open(owner, Unit, definition) }
                }
            }
            fixture.service.close()
            fixture.service.close()
            fixture.service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            assertTrue(fixture.drain().isEmpty())
            assertEquals(2, terminalEvents)
            assertTrue(handle.uiSession.status is UiSessionStatus.Closed)
            assertNull(fixture.service.capabilities(Unit))
            Host().use { replacement ->
                assertEquals(RemoteSessionStatus.Open, replacement.service.open(owner, Unit, definition).status)
            }
        }
    }

    @Test
    fun closingFromAReplacedConnectionListenerDoesNotInstallANewPeer() {
        Host().use { fixture ->
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Disconnected) fixture.service.close()
            }
            fixture.service.join(Unit)
            fixture.service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            fixture.service.tick()
            assertTrue(fixture.outgoing.isEmpty())
            assertEquals(1, fixture.events.filterIsInstance<RemoteLifecycleEvent.Disconnected>().size)
        }
    }

    @Test
    fun joiningAfterAQueuedServiceCloseDoesNotReviveTheConnection() {
        Host().use { fixture ->
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Opened) {
                    fixture.service.close()
                    fixture.service.join(Unit)
                }
            }
            val handle = fixture.service.open(Any(), Unit, UiDefinition { Spacer() })
            val snapshot = fixture.drain().filterIsInstance<RemoteMessage.Snapshot>().single()
            fixture.send(RemoteMessage.ControlApplied(handle.identity, checkNotNull(snapshot.control).sequence))
            fixture.service.enqueue(Unit, RemotePacket.encode(RemotePacket.Discovery))
            fixture.service.tick()
            assertTrue(fixture.outgoing.isEmpty())
            assertTrue(handle.uiSession.status is UiSessionStatus.Closed)
            assertEquals(1, fixture.events.filterIsInstance<RemoteLifecycleEvent.Disconnected>().size)
        }
    }

    @ParameterizedTest
    @EnumSource(Retirement::class)
    fun retiringAConnectionFromAReplacedScreenListenerDoesNotEvaluateTheNextDefinition(retirement: Retirement) {
        Host().use { fixture ->
            val owner = Any()
            val first = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
            fixture.drain()
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Closed && event.identity == first.identity) {
                    when (retirement) {
                        Retirement.Service -> fixture.service.close()
                        Retirement.Disconnect -> fixture.service.disconnect(Unit)
                        Retirement.Reconnect -> fixture.service.join(Unit)
                    }
                }
            }
            var evaluations = 0
            val result =
                runCatching {
                    fixture.service.open(
                        owner,
                        Unit,
                        UiDefinition {
                            evaluations += 1
                            Spacer()
                        },
                    )
                }
            assertEquals(0, evaluations)
            val replacement = result.getOrThrow()
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.Disconnected), replacement.status)
            assertTrue(replacement.uiSession.status is UiSessionStatus.Closed)
            assertNull(fixture.service.capabilities(Unit))
            assertEquals(listOf(first.identity, replacement.identity), fixture.events.filterIsInstance<RemoteLifecycleEvent.Closed<*>>().map { it.identity })
        }
    }

    @Test
    fun openingAScreenFromAReplacementListenerKeepsOneForeground() {
        Host().use { fixture ->
            val owner = Any()
            val first = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
            var newer: RemoteScreenSession? = null
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Closed && event.identity == first.identity) {
                    newer = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
                }
            }
            val pending = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
            val latest = checkNotNull(newer)
            assertEquals(1, listOf(pending, latest).count { it.status == RemoteSessionStatus.Open })
            assertEquals(listOf(RemoteFailure.Replaced), listOf(pending, latest).mapNotNull { (it.status as? RemoteSessionStatus.Closed)?.reason })
        }
    }

    @Test
    fun aNewerScreenOpenedDuringHudPromotionWinsTheForeground() {
        Host().use { fixture ->
            val owner = Any()
            val first = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
            val hud = fixture.service.open(owner, Unit, UiDefinition(presentation = UiPresentation.Hud) { Spacer() })
            fixture.drain().filterIsInstance<RemoteMessage.Snapshot>().forEach { snapshot ->
                fixture.send(RemoteMessage.ControlApplied(snapshot.session, checkNotNull(snapshot.control).sequence))
            }
            var newer: RemoteScreenSession? = null
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Closed && event.identity == first.identity) {
                    newer = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
                }
            }
            hud.uiSession.switch(UiPresentation.Screen)
            val latest = checkNotNull(newer)
            assertEquals(1, listOf(hud, latest).count { it.status == RemoteSessionStatus.Open })
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.Replaced), hud.status)
            assertEquals(RemoteSessionStatus.Open, latest.status)
        }
    }

    @Test
    fun aReporterFailureDoesNotStrandAnAlreadyQueuedServiceClose() {
        Host().use { fixture ->
            val owner = Any()
            val first = fixture.service.open(owner, Unit, UiDefinition { Spacer() })
            val failure = IllegalStateException("Definition failed.")
            var queued: RemoteScreenSession? = null
            fixture.listener = { event ->
                if (event is RemoteLifecycleEvent.Closed && event.identity == first.identity) {
                    queued = fixture.service.open(owner, Unit, UiDefinition { throw failure })
                    fixture.service.close()
                }
            }
            assertSame(failure, assertThrows(IllegalStateException::class.java) { fixture.service.open(owner, Unit, UiDefinition { Spacer() }) })
            assertTrue(checkNotNull(queued).status is RemoteSessionStatus.Closed)
            assertTrue(checkNotNull(queued).uiSession.status is UiSessionStatus.Closed)
            assertNull(fixture.service.capabilities(Unit))
            assertThrows(IllegalStateException::class.java) { fixture.service.join(Unit) }
        }
    }

    @Test
    fun disconnectingOrReplacingFromAnOpenedListenerStopsTheRetiredTick() {
        listOf<(RemoteScreenService<Unit, Any>) -> Unit>(
            { it.disconnect(Unit) },
            { it.join(Unit) },
        ).forEach { retire ->
            Host().use { fixture ->
                fixture.listener = { event ->
                    if (event is RemoteLifecycleEvent.Opened) retire(fixture.service)
                }
                val handle = fixture.service.open(Any(), Unit, UiDefinition { Spacer() })
                val snapshot = fixture.drain().filterIsInstance<RemoteMessage.Snapshot>().single()
                fixture.send(RemoteMessage.ControlApplied(handle.identity, checkNotNull(snapshot.control).sequence))
                assertTrue(handle.uiSession.status is UiSessionStatus.Closed)
                assertTrue(fixture.outgoing.isEmpty())
                assertTrue(fixture.events.last() is RemoteLifecycleEvent.Disconnected)
            }
        }
    }

    /**
     * Independent ways a close listener retires its authenticated connection.
     */
    enum class Retirement { Service, Disconnect, Reconnect }

    /**
     * Authenticated packet route with explicit native-application acknowledgements.
     */
    private class Host : AutoCloseable {
        val events = mutableListOf<RemoteLifecycleEvent<Any>>()
        var listener: (RemoteLifecycleEvent<Any>) -> Unit = {}
        val outgoing = ArrayDeque<ByteArray>()
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
