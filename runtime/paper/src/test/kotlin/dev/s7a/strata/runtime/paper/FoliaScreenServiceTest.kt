package dev.s7a.strata.runtime.paper

import dev.s7a.strata.component.Spacer
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
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.runtime.remote.RemoteSessionStatus
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.mutableStateOf
import io.papermc.paper.threadedregions.scheduler.EntityScheduler
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.logging.Logger

/**
 * Uses real Paper scheduler interfaces and protocol frames to exercise migrating entity ownership without a game.
 */
internal class FoliaScreenServiceTest {
    @Test
    fun aPlayerMigratesThreadsAndKeepsReactiveStateAndAuthenticatedActions() {
        Harness().use { fixture ->
            val player = fixture.PlayerFixture()
            player.join()
            var state: MutableState<Int>? = null
            val handle =
                player.region {
                    fixture.host.open(fixture.plugin, player.player) {
                        val counter = mutableStateOf(0)
                        state = counter
                        ScreenDefinition("Migrating") {
                            counter.value
                            Spacer(Modifier.Empty.onActivate { counter.value += 1 })
                        }
                    }
                }
            val snapshot = player.receive().filterIsInstance<RemoteMessage.Snapshot>().single()
            player.action(snapshot)
            val executor = Executors.newSingleThreadExecutor()
            try {
                executor
                    .submit {
                        player.region {
                            player.tick()
                            fixture.host.execute(player.player) { assertEquals(1, checkNotNull(state).value) }
                        }
                    }.get(5, TimeUnit.SECONDS)
                player.region { fixture.host.execute(player.player) { assertEquals(1, checkNotNull(state).value) } }
                assertEquals(RemoteSessionStatus.Open, handle.status)
                assertThrows(IllegalStateException::class.java) { checkNotNull(state).value }
                assertThrows(IllegalStateException::class.java) { fixture.host.capabilities(player.player) }
                val other = fixture.PlayerFixture()
                other.join()
                other.region {
                    assertThrows(IllegalStateException::class.java) {
                        fixture.host.execute(other.player) { checkNotNull(state).value }
                    }
                }
            } finally {
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun pluginDisableRetiresQueuedActionsBeforeTheirHandlersRun() {
        Harness().use { fixture ->
            val player = fixture.PlayerFixture()
            player.join()
            var calls = 0
            val handle = player.open { calls++ }
            player.action(player.receive().filterIsInstance<RemoteMessage.Snapshot>().single())
            fixture.host.ownerDisabled(fixture.plugin)
            player.region { player.tick() }
            assertEquals(0, calls)
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed), handle.status)
        }
    }

    @Test
    fun disconnectRetirementAndShutdownReleaseHandlesAndCancelTickers() {
        Harness().use { fixture ->
            val disconnected = fixture.PlayerFixture()
            disconnected.join()
            val first = disconnected.open {}
            disconnected.region { fixture.host.disconnect(disconnected.player) }
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.Disconnected), first.status)
            assertTrue(disconnected.cancelled)
            disconnected.region { disconnected.tick() }
            val retired = fixture.PlayerFixture()
            retired.join()
            val second = retired.open {}
            retired.region { checkNotNull(retired.retire).run() }
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.Disconnected), second.status)
            assertTrue(retired.cancelled)
            retired.region { assertNull(fixture.host.capabilities(retired.player)) }
            val stopped = fixture.PlayerFixture()
            stopped.join()
            val third = stopped.open {}
            fixture.host.close()
            assertEquals(RemoteSessionStatus.Closed(RemoteFailure.OwnerClosed), third.status)
            assertTrue(stopped.cancelled)
            stopped.region { stopped.tick() }
        }
    }

    @Test
    fun anAlreadyRetiredSchedulerDoesNotRetainAConnection() {
        Harness().use { fixture ->
            val player = fixture.PlayerFixture(alreadyRetired = true)
            player.region {
                fixture.host.join(player.player)
                assertNull(fixture.host.capabilities(player.player))
            }
        }
    }

    /**
     * Decodes reflected API methods at a fake platform boundary; all UI and transport code is production code.
     */
    @Suppress("StringLiteralComparison")
    private class Harness : AutoCloseable {
        private val region = ThreadLocal<Player>()
        val plugin: Plugin =
            proxy(Plugin::class.java) { name, _ ->
                when (name) {
                    "isEnabled" -> true
                    "getLogger" -> Logger.getLogger("strata-folia-test")
                    else -> error("Unexpected plugin method: $name")
                }
            }
        val host = FoliaScreenService(plugin) { player -> region.get() === player }

        override fun close() {
            host.close()
        }

        /**
         * Authenticated player and a scheduler whose tick can move to another real JVM thread.
         */
        inner class PlayerFixture(
            private val alreadyRetired: Boolean = false,
        ) {
            private val outgoing = ConcurrentLinkedQueue<ByteArray>()
            private var callback: Consumer<ScheduledTask>? = null
            var retire: Runnable? = null
            var cancelled = false
            private val task: ScheduledTask =
                proxy(ScheduledTask::class.java) { name, _ ->
                    when (name) {
                        "cancel" -> {
                            cancelled = true
                            ScheduledTask.CancelledState.CANCELLED_BY_CALLER
                        }

                        else -> {
                            error("Unexpected task method: $name")
                        }
                    }
                }
            private val scheduler: EntityScheduler =
                proxy(EntityScheduler::class.java) { name, args ->
                    when (name) {
                        "runAtFixedRate" -> {
                            @Suppress("UNCHECKED_CAST")
                            val scheduled = args[1] as Consumer<ScheduledTask>
                            callback = scheduled
                            retire = args[2] as Runnable
                            if (alreadyRetired) null else task
                        }

                        else -> {
                            error("Unexpected scheduler method: $name")
                        }
                    }
                }
            val player: Player =
                proxy(Player::class.java) { name, args ->
                    when (name) {
                        "getScheduler" -> {
                            scheduler
                        }

                        "sendPluginMessage" -> {
                            check(region.get() === player) { "Native delivery escaped the player region." }
                            outgoing.add(args[2] as ByteArray)
                            null
                        }

                        else -> {
                            error("Unexpected player method: $name")
                        }
                    }
                }
            private var address: RemoteAddress? = null
            private var sequence = 1L
            private val client =
                RemoteConnection(RemoteRegistry().also(RemoteBuiltins::register).types, RemotePacket.limits) { bytes ->
                    host.enqueue(player, RemotePacket.encode(RemotePacket.Frame(checkNotNull(address), sequence++, bytes)))
                }

            fun <T> region(operation: () -> T): T {
                val previous = region.get()
                region.set(player)
                return try {
                    operation()
                } finally {
                    if (previous == null) region.remove() else region.set(previous)
                }
            }

            fun join() {
                region { host.join(player) }
                host.enqueue(player, RemotePacket.encode(RemotePacket.Discovery))
                region { tick() }
                receive()
                client.flush()
                region {
                    tick()
                    checkNotNull(host.capabilities(player))
                }
            }

            fun open(action: () -> Unit): RemoteScreenSession =
                region {
                    host.open(plugin, player) { ScreenDefinition("Folia") { Spacer(Modifier.Empty.onActivate(action)) } }
                }

            fun tick() {
                checkNotNull(callback).accept(task)
            }

            fun receive(): List<RemoteMessage> {
                region { tick() }
                return buildList {
                    while (true) {
                        val bytes = outgoing.poll() ?: break
                        val frame = RemotePacket.decode(bytes) as RemotePacket.Frame
                        address = frame.address
                        client.receive(frame.bytes, 0)?.let(::add)
                    }
                }
            }

            fun action(snapshot: RemoteMessage.Snapshot) {
                val press =
                    snapshot.tree.nodes.values
                        .flatMap { it.modifiers }
                        .single { it.type == BuiltinProjection.PointerPress.type }
                val endpoint = ((press.value as ProjectionValue.Sequence).values[1] as ProjectionValue.Integer).value
                client.send(RemoteMessage.Action(snapshot.session, 1, endpoint, press.type, ProjectionInputCodec.pointer(PointerEvent.Press(IntOffset.Zero, PointerButton.Primary), IntOffset.Zero)))
                client.flush()
            }
        }

        private fun <T> proxy(
            type: Class<T>,
            invoke: (String, Array<out Any?>) -> Any?,
        ): T =
            type.cast(
                Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, arguments ->
                    when (method.name) {
                        "hashCode" -> System.identityHashCode(instance)
                        "equals" -> instance === arguments?.get(0)
                        "toString" -> type.simpleName
                        else -> invoke(method.name, arguments ?: emptyArray())
                    }
                },
            )
    }
}
