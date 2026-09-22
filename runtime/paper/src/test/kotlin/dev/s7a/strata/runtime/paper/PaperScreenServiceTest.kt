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
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteCanvas
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.mutableStateOf
import org.bukkit.entity.Player
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
    fun anActionCanReplaceItsOwnScreenAfterTheHandlerReturns() {
        Harness().use { fixture ->
            fixture.negotiate()
            var replacement: PaperScreenSession? = null
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
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed), handle.status)
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
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed), handle.status)
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

    /**
     * Real codec and connection exercised through lightweight Bukkit interface proxies.
     */
    @Suppress("StringLiteralComparison") // Java proxy dispatch decodes external reflected method names at the test adapter boundary.
    private class Harness(
        extraTypes: Set<ProjectionType> = emptySet(),
    ) : AutoCloseable {
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
        val service = PaperScreenService(plugin)
        val client = RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types + extraTypes) { service.enqueue(player, it) }

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
            client.start()
            client.flush()
            assertTrue(receive().isEmpty())
            assertTrue(service.capabilities(player) != null)
        }

        fun receive(): List<RemoteMessage> {
            service.tick()
            val result = mutableListOf<RemoteMessage>()
            while (messages.isNotEmpty()) client.receive(messages.removeFirst(), 0)?.let(result::add)
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
