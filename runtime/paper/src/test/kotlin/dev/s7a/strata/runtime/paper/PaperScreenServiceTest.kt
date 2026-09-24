@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.paper

import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.paper.event.StrataClientDisconnectedEvent
import dev.s7a.strata.paper.event.StrataClientReadyEvent
import dev.s7a.strata.paper.event.StrataUiClosedEvent
import dev.s7a.strata.paper.event.StrataUiOpenedEvent
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteCanvas
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiOperationResult
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiRejection
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.logging.Logger

/**
 * Verifies plugin ownership and authenticated queued actions with the real Paper API and an in-memory transport.
 */
internal class PaperScreenServiceTest {
    @Test
    fun paperEventsUseTheNativeHandlerListsAfterClientApplication() {
        Harness().use { fixture ->
            fixture.negotiate()
            val ready = fixture.events.single() as StrataClientReadyEvent
            assertEquals(fixture.player, ready.player)
            assertEquals(16, ready.capabilities.hudLimit)
            assertTrue(ready.isAsynchronous.not())
            val handle = fixture.service.open(fixture.plugin, fixture.player, UiDefinition { Spacer() })
            val snapshot = fixture.receive().filterIsInstance<RemoteMessage.Snapshot>().single()
            assertEquals(1, fixture.events.size)
            fixture.client.send(RemoteMessage.ControlApplied(handle.identity, checkNotNull(snapshot.control).sequence))
            fixture.client.flush()
            fixture.service.tick()
            val opened = fixture.events.last() as StrataUiOpenedEvent
            assertEquals(handle.uiSession, opened.session)
            assertEquals(fixture.plugin, opened.ownerPlugin)
            assertEquals(StrataUiOpenedEvent.getHandlerList(), opened.handlers)
            assertTrue(opened.handlers !== ready.handlers)
            fixture.service.disconnect(fixture.player)
            assertEquals(1, fixture.events.filterIsInstance<StrataUiClosedEvent>().size)
            assertTrue(fixture.events.last() is StrataClientDisconnectedEvent)
        }
    }

    @Test
    fun anActionCanReplaceItsOwnScreenAfterTheHandlerReturns() {
        Harness().use { fixture ->
            fixture.negotiate()
            var replacement: RemoteScreenSession? = null
            val first =
                fixture.service.open(
                    fixture.plugin,
                    fixture.player,
                    ScreenDefinition("First") {
                        Spacer(
                            Modifier.Empty.onActivate {
                                replacement = fixture.service.open(fixture.plugin, fixture.player, ScreenDefinition("Second") { Spacer() })
                                assertEquals(RemoteSessionStatus.Opening, replacement.status)
                            },
                        )
                    },
                )
            val snapshot = fixture.receive().filterIsInstance<RemoteMessage.Snapshot>().single()
            val endpoint =
                snapshot.tree.nodes.values
                    .flatMap { it.modifiers }
                    .single { it.type == BuiltinProjection.PointerPress.type }
                    .value
                    .let { (it as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer }
            fixture.client.send(RemoteMessage.Action(first.identity, 1, endpoint.value, BuiltinProjection.PointerPress.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.Replaced), first.status)
            assertEquals(RemoteSessionStatus.Open, replacement?.status)
            assertEquals(
                replacement?.identity,
                fixture
                    .receive()
                    .filterIsInstance<RemoteMessage.Snapshot>()
                    .single()
                    .session,
            )
        }
    }

    @Test
    fun clientFailurePreservesItsTypedTerminalReasonOnTheOwnerHandle() {
        Harness().use { fixture ->
            fixture.negotiate()
            val handle = fixture.service.open(fixture.plugin, fixture.player, ScreenDefinition("Failure") { Spacer() })
            fixture.receive()
            fixture.client.send(RemoteMessage.Close(handle.identity, RemoteFailure.InvalidMessage))
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.InvalidMessage), handle.status)
        }
    }

    @Test
    fun replacingNativeContainerRetiresTheScreenAndRejectsItsPendingAction() {
        Harness().use { fixture ->
            fixture.negotiate()
            var clicks = 0
            val handle =
                fixture.service.open(
                    fixture.plugin,
                    fixture.player,
                    ScreenDefinition("Container") {
                        Spacer(Modifier.Empty.size(20, 20).onActivate { clicks++ })
                    },
                )
            val snapshot = fixture.receive().filterIsInstance<RemoteMessage.Snapshot>().single()
            val action =
                snapshot.tree.nodes.values
                    .flatMap { it.modifiers }
                    .single { it.type == BuiltinProjection.PointerPress.type }
                    .value
                    .let { (it as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer }
            fixture.client.send(RemoteMessage.Action(handle.identity, 1, action.value, BuiltinProjection.PointerPress.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            fixture.client.flush()
            fixture.service.containerChanged(fixture.player)
            fixture.service.tick()
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.ContainerChanged), handle.status)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun missingClientExtensionRejectsOpeningAndDisablingItsOwnerClosesDependentScreens() {
        val type = ProjectionType(ResourceId("test", "canvas"))
        Harness().use { fixture ->
            fixture.service.register(fixture.plugin, type)
            fixture.negotiate()
            val handle =
                fixture.service.open(
                    fixture.plugin,
                    fixture.player,
                    ScreenDefinition("Extension") {
                        Canvas(RemoteCanvas.source(type, true, ProjectionValue::Flag), IntSize(20, 20))
                    },
                )
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.UnsupportedType), handle.status)
        }
        Harness(setOf(type)).use { fixture ->
            val extensionOwner = fixture.otherPlugin()
            fixture.service.register(extensionOwner, type)
            fixture.negotiate()
            val handle =
                fixture.service.open(
                    fixture.plugin,
                    fixture.player,
                    ScreenDefinition("Extension") {
                        Canvas(RemoteCanvas.source(type, true, ProjectionValue::Flag), IntSize(20, 20))
                    },
                )
            assertEquals(RemoteSessionStatus.Open, handle.status)
            fixture.service.ownerDisabled(extensionOwner)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerDisabled), handle.status)
        }
    }

    @Test
    fun opensOnlyNegotiatedClientsAndRunsHandlersFromTheTick() {
        Harness().use { fixture ->
            val unavailable = fixture.service.open(fixture.plugin, fixture.player, ScreenDefinition("Remote test") { Spacer() })
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.UnsupportedProtocol), unavailable.status)
            fixture.negotiate()
            val clicks = mutableStateOf(0)
            val handle =
                fixture.service.open(
                    fixture.plugin,
                    fixture.player,
                    ScreenDefinition("Remote test") {
                        Spacer(
                            Modifier.Empty
                                .size(20, 20)
                                .background(ArgbColor(clicks.value))
                                .onActivate { clicks.value += 1 },
                        )
                    },
                )
            assertEquals(RemoteSessionStatus.Open, handle.status)
            val snapshot = fixture.receive().filterIsInstance<RemoteMessage.Snapshot>().single()
            val endpoint =
                snapshot.tree.nodes.values
                    .flatMap { it.modifiers }
                    .single { it.type == BuiltinProjection.PointerPress.type }
                    .value
                    .let { (it as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer }
            fixture.client.send(RemoteMessage.Action(handle.identity, 1, endpoint.value, BuiltinProjection.PointerPress.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            fixture.client.flush()
            assertEquals(0, clicks.value)
            fixture.service.tick()
            assertEquals(1, clicks.value)
            fixture.service.ownerDisabled(fixture.plugin)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerDisabled), handle.status)
        }
    }

    @Test
    fun replacementAndDisconnectReleasePriorHandles() {
        Harness().use { fixture ->
            fixture.negotiate()
            val first = fixture.service.open(fixture.plugin, fixture.player, ScreenDefinition("Remote test") { Spacer() })
            val second = fixture.service.open(fixture.plugin, fixture.player, ScreenDefinition("Remote test") { Spacer() })
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.Replaced), first.status)
            fixture.service.disconnect(fixture.player)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.Disconnected), second.status)
            assertNull(fixture.service.capabilities(fixture.player))
        }
    }

    @Test
    fun staleSessionActionsCannotInvokeTheReplacementScreen() {
        Harness().use { fixture ->
            fixture.negotiate()
            val first = fixture.service.open(fixture.plugin, fixture.player, ScreenDefinition("Remote test") { Spacer() })
            val second = fixture.service.open(fixture.plugin, fixture.player, ScreenDefinition("Remote test") { Spacer() })
            fixture.client.send(RemoteMessage.Action(first.identity, 1, 1, BuiltinProjection.PointerPress.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(RemoteSessionStatus.Open, second.status)
        }
    }

    @Test
    fun independentPluginHudsKeepTheirEventOwnerAndSurviveUnrelatedContainerChanges() {
        Harness().use { fixture ->
            fixture.negotiate()
            val other = fixture.otherPlugin()
            val receivers = mutableListOf<UiSession>()
            val shared =
                Modifier.Empty.onActivate {
                    receivers += this
                    close()
                }
            val first = fixture.service.open(fixture.plugin, fixture.player, UiDefinition(presentation = UiPresentation.Hud) { Spacer(shared) })
            val second = fixture.service.open(other, fixture.player, UiDefinition(presentation = UiPresentation.Hud) { Spacer(shared) })
            val snapshots = fixture.receive().filterIsInstance<RemoteMessage.Snapshot>()
            assertEquals(2, snapshots.size)
            assertNull(first.uiSession.presentation)
            snapshots.forEach { fixture.client.send(RemoteMessage.ControlApplied(it.session, checkNotNull(it.control).sequence)) }
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(UiPresentation.Hud, first.uiSession.presentation)
            fixture.service.containerChanged(fixture.player)
            assertEquals(RemoteSessionStatus.Open, first.status)
            assertEquals(RemoteSessionStatus.Open, second.status)
            val snapshot = snapshots.single { it.session == second.identity }
            val endpoint =
                snapshot.tree.nodes.values
                    .flatMap { it.modifiers }
                    .single { it.type == BuiltinProjection.PointerPress.type }
                    .value as ProjectionValue.Sequence
            val endpointId = (endpoint.values[1] as ProjectionValue.Integer).value
            fixture.client.send(RemoteMessage.Action(second.identity, 1, endpointId, BuiltinProjection.PointerPress.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(listOf(second.uiSession), receivers)
            assertEquals(UiSessionStatus.Closed(UiCloseReason.Closed), second.uiSession.status)
            assertEquals(RemoteSessionStatus.Open, first.status)
            fixture.service.ownerDisabled(fixture.plugin)
            assertEquals(UiSessionStatus.Closed(UiCloseReason.OwnerDisabled), first.uiSession.status)
        }
    }

    @Test
    fun negotiatedHudCapacityRejectsOpeningAndSwitchingWithoutClosingTheExistingScreen() {
        Harness(limits = RemoteLimits(hudSessions = 1)).use { fixture ->
            fixture.negotiate()
            val hud = fixture.service.open(fixture.plugin, fixture.player, UiDefinition(presentation = UiPresentation.Hud) { Spacer() })
            val ordinary = fixture.service.open(fixture.plugin, fixture.player, UiDefinition { Spacer() })
            val rejected = fixture.service.open(fixture.plugin, fixture.player, UiDefinition(presentation = UiPresentation.Hud) { Spacer() })
            assertEquals(UiSessionStatus.Closed(UiCloseReason.ResourceLimit), rejected.uiSession.status)
            fixture.receive().filterIsInstance<RemoteMessage.Snapshot>().forEach {
                fixture.client.send(RemoteMessage.ControlApplied(it.session, checkNotNull(it.control).sequence))
            }
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(UiOperationResult.Rejected(UiRejection.Capacity), ordinary.uiSession.switch(UiPresentation.Hud))
            assertEquals(UiPresentation.Screen, ordinary.uiSession.presentation)
            assertEquals(RemoteSessionStatus.Open, hud.status)
            assertEquals(RemoteSessionStatus.Open, ordinary.status)
        }
    }

    @Test
    fun rapidRemoteSwitchesKeepAppliedPresentationUntilLatestReplyAndCloseSupersedesIt() {
        Harness().use { fixture ->
            fixture.negotiate()
            val handle = fixture.service.open(fixture.plugin, fixture.player, UiDefinition { Spacer() })
            val initial = fixture.receive().filterIsInstance<RemoteMessage.Snapshot>().single()
            fixture.client.send(RemoteMessage.ControlApplied(handle.identity, checkNotNull(initial.control).sequence))
            fixture.client.flush()
            fixture.service.tick()
            handle.uiSession.switch(UiPresentation.Hud)
            handle.uiSession.switch(UiPresentation.Screen)
            val requests = fixture.receive().filterIsInstance<RemoteMessage.Control>()
            assertEquals(2, requests.size)
            fixture.client.send(RemoteMessage.ControlApplied(handle.identity, requests.first().state.sequence))
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(UiSessionStatus.Switching(UiPresentation.Screen), handle.uiSession.status)
            handle.uiSession.close()
            fixture.client.send(RemoteMessage.ControlApplied(handle.identity, requests.last().state.sequence))
            fixture.client.flush()
            fixture.service.tick()
            assertEquals(UiSessionStatus.Closed(UiCloseReason.Closed), handle.uiSession.status)
            assertEquals(UiPresentation.Screen, handle.uiSession.presentation)
        }
    }

    /**
     * Real codec and connection exercised through lightweight Bukkit interface proxies.
     */
    @Suppress("StringLiteralComparison") // Java proxy dispatch decodes external reflected method names at the test adapter boundary.
    private class Harness(
        extraTypes: Set<ProjectionType> = emptySet(),
        limits: RemoteLimits = RemoteLimits(),
    ) : AutoCloseable {
        val events = mutableListOf<Event>()
        private val messages = ArrayDeque<ByteArray>()
        private var channelRegistered = false
        val plugin: Plugin =
            proxy(Plugin::class.java) { name, _ ->
                when (name) {
                    "getLogger" -> Logger.getLogger("strata-paper-test")
                    else -> error("Unexpected plugin API: $name")
                }
            }
        val player: Player =
            proxy(Player::class.java) { name, arguments ->
                when (name) {
                    "sendPluginMessage" -> {
                        check(channelRegistered) { "Paper cannot send before the client registers its channel." }
                        messages.addLast(arguments[2] as ByteArray)
                        null
                    }

                    else -> {
                        error("Unexpected player API: $name")
                    }
                }
            }
        val service = paperScreenService(plugin) { events.add(it) }
        private var address: RemoteAddress? = null
        private var sequence = 1L
        val client = RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types + extraTypes, limits) { service.enqueue(player, RemotePacket.encode(RemotePacket.Frame(checkNotNull(address), sequence++, it))) }

        fun otherPlugin(): Plugin =
            proxy(Plugin::class.java) { name, _ ->
                when (name) {
                    "getLogger" -> Logger.getLogger("strata-extension-test")
                    else -> error("Unexpected extension API: $name")
                }
            }

        fun negotiate() {
            service.join(player)
            assertTrue(receive().isEmpty())
            channelRegistered = true
            service.enqueue(player, RemotePacket.encode(RemotePacket.Discovery))
            assertTrue(receive().isEmpty())
            client.flush()
            service.tick()
            assertTrue(service.capabilities(player) != null)
        }

        fun receive(): List<RemoteMessage> {
            service.tick()
            val result = mutableListOf<RemoteMessage>()
            while (messages.isNotEmpty()) {
                val packet = RemotePacket.decode(messages.removeFirst()) as RemotePacket.Frame
                address = packet.address
                client.receive(packet.bytes, 0)?.let(result::add)
            }
            return result
        }

        override fun close() {
            service.close()
            client.close()
        }

        private fun <T> proxy(
            type: Class<T>,
            invoke: (String, Array<out Any?>) -> Any?,
        ): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, arguments ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === arguments?.get(0)
                        "toString" -> type.simpleName
                        else -> invoke(method.name, arguments ?: emptyArray())
                    }
                },
            )
    }
}
