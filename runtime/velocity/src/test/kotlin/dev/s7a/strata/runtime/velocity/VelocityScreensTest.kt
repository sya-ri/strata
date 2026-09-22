package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.event.Continuation
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.PluginContainer
import com.velocitypowered.api.plugin.PluginManager
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.ChannelRegistrar
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionInputCodec
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.runtime.remote.RemoteAddress
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemoteProtocolException
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.screen.ScreenDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.lang.reflect.Proxy
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs the actual Velocity plugin API, worker, routing, and common protocol against authenticated API doubles.
 */
internal class VelocityScreensTest {
    @Test
    fun boundsPendingApiWorkAndCompletesRequestsWhenShuttingDown() {
        Harness().use { fixture ->
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            val current =
                VelocityScreens.execute(fixture.owner) {
                    entered.countDown()
                    check(proceed.await(5, TimeUnit.SECONDS))
                }
            check(entered.await(5, TimeUnit.SECONDS))
            val pending =
                try {
                    List(1025) { VelocityScreens.capabilities(fixture.player) }.also { requests ->
                        val failure = assertThrows(ExecutionException::class.java) { requests.last().get(5, TimeUnit.SECONDS) }
                        assertEquals(RemoteFailure.ResourceLimit, (failure.cause as RemoteProtocolException).reason)
                    }
                } finally {
                    proceed.countDown()
                }
            fixture.close()
            assertTrue(current.isDone)
            assertTrue(pending.all { it.isDone })
        }
    }

    @Test
    fun constructsStateAndRunsActionsOnOneOwnedThread() {
        Harness().use { fixture ->
            fixture.negotiate()
            val createdOn = AtomicReference<Thread>()
            val calls = AtomicInteger()
            val handle =
                VelocityScreens
                    .open(fixture.owner, fixture.player) {
                        createdOn.set(Thread.currentThread())
                        val field = TextFieldState("before")
                        ScreenDefinition("Proxy") {
                            Spacer(
                                Modifier.Empty.onActivate {
                                    assertEquals(createdOn.get(), Thread.currentThread())
                                    field.value = "after"
                                    calls.incrementAndGet()
                                },
                            )
                        }
                    }.get(5, TimeUnit.SECONDS)
            assertNotSame(Thread.currentThread(), createdOn.get())
            val snapshot = fixture.nextMessage() as RemoteMessage.Snapshot
            fixture.activate(snapshot)
            assertTrue(fixture.nextMessage() is RemoteMessage.Acknowledgement)
            fixture.activate(snapshot)
            assertTrue(fixture.nextMessage() is RemoteMessage.Acknowledgement)
            assertEquals(1, calls.get())
            handle.close()
            assertTrue(fixture.nextMessage() is RemoteMessage.Close)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed), handle.status)
        }
    }

    @Test
    fun backendReplacementRetiresPendingActionsAndRediscoversPaper() {
        Harness().use { fixture ->
            fixture.negotiate()
            val calls = AtomicInteger()
            val handle =
                VelocityScreens
                    .open(fixture.owner, fixture.player) {
                        ScreenDefinition("Before switch") { Spacer(Modifier.Empty.onActivate { calls.incrementAndGet() }) }
                    }.get(5, TimeUnit.SECONDS)
            val snapshot = fixture.nextMessage() as RemoteMessage.Snapshot
            fixture.currentBackend.set(fixture.replacementBackend())
            fixture.backendMessages.clear()
            fixture.plugin.connected(ServerPostConnectEvent(fixture.player, null))
            val discovery = checkNotNull(fixture.backendMessages.poll(5, TimeUnit.SECONDS))
            assertEquals(RemotePacket.Discovery, RemotePacket.decode(discovery))
            fixture.activate(snapshot)
            assertTrue(fixture.nextMessage() is RemoteMessage.Close)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.ContainerChanged), handle.status)
            assertEquals(0, calls.get())
            assertTrue(VelocityScreens.capabilities(fixture.player).get(5, TimeUnit.SECONDS) != null)
        }
    }

    @Test
    fun refusesBackendProxyImpersonationAndNeverForwardsProxyActions() {
        Harness().use { fixture ->
            val serverFrame = RemotePacket.encode(RemotePacket.Frame(RemoteAddress(RemoteEndpoint.Server), 1, ByteArray(17)))
            val proxyFrame = RemotePacket.encode(RemotePacket.Frame(RemoteAddress(RemoteEndpoint.Proxy), 1, ByteArray(17)))

            fun fromBackend(bytes: ByteArray): PluginMessageEvent = PluginMessageEvent(fixture.backend, fixture.player, VelocityScreenService.CHANNEL, bytes).also { fixture.plugin.message(it) }
            assertFalse(fromBackend(serverFrame).result.isAllowed)
            assertTrue(fixture.outgoing.poll(5, TimeUnit.SECONDS)?.contentEquals(serverFrame) == true)
            assertFalse(fromBackend(proxyFrame).result.isAllowed)
            assertFalse(fromBackend(RemotePacket.encode(RemotePacket.Discovery)).result.isAllowed)
            assertFalse(fixture.send(proxyFrame).result.isAllowed)
            assertFalse(fixture.send(serverFrame).result.isAllowed)
            assertTrue(fixture.backendMessages.poll(5, TimeUnit.SECONDS)?.contentEquals(serverFrame) == true)
            fixture.currentBackend.set(null)
            assertFalse(fromBackend(serverFrame).result.isAllowed)
        }
    }

    @Test
    fun ownerReleaseAndShutdownDropAuthoritativeReferences() {
        Harness().use { fixture ->
            fixture.negotiate()
            val first = VelocityScreens.open(fixture.owner, fixture.player) { ScreenDefinition("Owner") { Spacer() } }.get(5, TimeUnit.SECONDS)
            fixture.nextMessage()
            VelocityScreens.release(fixture.owner).get(5, TimeUnit.SECONDS)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed), first.status)
            fixture.nextMessage()
            val second = VelocityScreens.open(fixture.owner, fixture.player) { ScreenDefinition("Shutdown") { Spacer() } }.get(5, TimeUnit.SECONDS)
            fixture.nextMessage()
            fixture.close()
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed), second.status)
        }
    }

    /**
     * Owns one native-channel double and drains real worker output through the client codec.
     */
    @Suppress("StringLiteralComparison") // Dispatches Java reflection method names at the test-double boundary.
    private class Harness : AutoCloseable {
        val owner = Any()
        val outgoing = LinkedBlockingQueue<ByteArray>()
        val backendMessages = LinkedBlockingQueue<ByteArray>()
        val currentBackend = AtomicReference<ServerConnection?>()
        val player: Player =
            proxy(Player::class.java) { name, arguments ->
                when (name) {
                    "isActive" -> true
                    "getCurrentServer" -> Optional.ofNullable(currentBackend.get())
                    "sendPluginMessage" -> outgoing.offer(arguments[1] as ByteArray)
                    else -> error("Unexpected player API: $name")
                }
            }
        val backend: ServerConnection = replacementBackend()

        fun replacementBackend(): ServerConnection =
            proxy(ServerConnection::class.java) { name, arguments ->
                when (name) {
                    "getPlayer" -> player
                    "sendPluginMessage" -> backendMessages.offer(arguments[1] as ByteArray)
                    else -> error("Unexpected backend API: $name")
                }
            }

        private val container = proxy(PluginContainer::class.java) { _, _ -> error("No plugin-container access expected.") }
        private val plugins =
            proxy(PluginManager::class.java) { name, arguments ->
                check(name == "fromInstance")
                if (arguments[0] === owner) Optional.of(container) else Optional.empty<PluginContainer>()
            }
        private val registrar =
            proxy(ChannelRegistrar::class.java) { name, _ ->
                check(name in setOf("register", "unregister"))
                null
            }
        private val server =
            proxy(ProxyServer::class.java) { name, _ ->
                when (name) {
                    "getPluginManager" -> plugins
                    "getChannelRegistrar" -> registrar
                    else -> error("Unexpected proxy API: $name")
                }
            }
        val plugin = StrataVelocityPlugin(server, LoggerFactory.getLogger(VelocityScreensTest::class.java))
        private var address: RemoteAddress? = null
        private var sequence = 1L
        private val client = RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types, RemotePacket.limits) { send(RemotePacket.encode(RemotePacket.Frame(checkNotNull(address), sequence++, it))) }
        private var closed = false

        init {
            currentBackend.set(backend)
            plugin.initialize(ProxyInitializeEvent())
        }

        fun send(bytes: ByteArray): PluginMessageEvent = PluginMessageEvent(player, backend, VelocityScreenService.CHANNEL, bytes).also { plugin.message(it) }

        fun negotiate() {
            assertFalse(send(RemotePacket.encode(RemotePacket.Discovery)).result.isAllowed)
            val greeting = RemotePacket.decode(checkNotNull(outgoing.poll(5, TimeUnit.SECONDS))) as RemotePacket.Frame
            address = greeting.address
            assertEquals(RemoteEndpoint.Proxy, greeting.address.endpoint)
            assertEquals(null, client.receive(greeting.bytes, 0))
            client.flush()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (VelocityScreens.capabilities(player).get(5, TimeUnit.SECONDS) != null) return
            }
            error("Proxy negotiation did not complete.")
        }

        fun nextMessage(): RemoteMessage {
            while (true) {
                val packet = RemotePacket.decode(checkNotNull(outgoing.poll(5, TimeUnit.SECONDS)) { "Missing proxy output." }) as RemotePacket.Frame
                client.receive(packet.bytes, 0)?.let { return it }
            }
        }

        fun activate(snapshot: RemoteMessage.Snapshot) {
            val press =
                snapshot.tree.nodes.values
                    .flatMap { it.modifiers }
                    .single { it.type == BuiltinProjection.PointerPress.type }
            val endpoint = ((press.value as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer).value
            client.send(RemoteMessage.Action(snapshot.session, 1, endpoint, press.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
            client.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            val completion = CompletableFuture<Unit>()
            plugin.shutdown(ProxyShutdownEvent())?.execute(
                object : Continuation {
                    override fun resume() {
                        completion.complete(Unit)
                    }

                    override fun resumeWithException(exception: Throwable) {
                        completion.completeExceptionally(exception)
                    }
                },
            ) ?: completion.complete(Unit)
            completion.get(5, TimeUnit.SECONDS)
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
