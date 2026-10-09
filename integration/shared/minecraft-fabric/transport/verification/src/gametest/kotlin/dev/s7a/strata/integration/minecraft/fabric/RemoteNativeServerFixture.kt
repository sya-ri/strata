package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Canvas
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.fabric.Strata
import dev.s7a.strata.runtime.minecraft.fabric.StrataUiEvent
import dev.s7a.strata.runtime.remote.RemoteCanvas
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import dev.s7a.strata.ui.UiSessionStatus
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiled server consumer proving the installed public API on the actual logical server owner.
 * Networking, authentication, session orchestration, and shutdown belong to the production runtime.
 */
public object RemoteNativeServerFixture {
    /**
     * Detached renderer schema advertised by the public server adapter.
     */
    public val type: ProjectionType = ProjectionType(ResourceId("strata_test", "native_canvas"))
    private val serverPlayers = mutableMapOf<MinecraftServer, MutableSet<ServerPlayer>>()
    private var dedicatedConnections = 0
    private var dedicatedCompletions = 0
    private val peers = mutableMapOf<ServerPlayer, Verification>()
    private val owner get() = FabricLoader.getInstance().getModContainer("strata").orElseThrow()

    /**
     * Registers the detached schema before negotiation and retires test-only observations at shutdown.
     */
    public fun initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            serverPlayers[server] = mutableSetOf()
            Strata.register(server, owner, type)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server -> serverPlayers.getValue(server).add(handler.player) }
        ServerTickEvents.END_SERVER_TICK.register { server ->
            server.playerList.players.forEach { player -> peers[player]?.tick() }
            System.getProperty("strata.fabric.stop")?.let { if (Files.exists(Path.of(it))) server.halt(false) }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            server.execute {
                peers.remove(handler.player)
                serverPlayers[server]?.remove(handler.player)
            }
        }
        ServerLifecycleEvents.SERVER_STOPPED.register { server ->
            serverPlayers.remove(server)?.forEach(peers::remove)
            System.getProperty("strata.fabric.receipt")?.let { receipt ->
                val output = Path.of(receipt)
                if (Files.exists(output)) Files.writeString(output, Files.readString(output) + "shutdown=confirmed\n")
            }
        }
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            if (server.isDedicatedServer.not()) return@register
            val run = System.getProperty("strata.fabric.run") ?: return@register
            Strata.listen(server, owner) { event ->
                check(server.isSameThread)
                when (event) {
                    is StrataUiEvent.Ready -> {
                        dedicatedConnections++
                        open(event.player)
                    }

                    is StrataUiEvent.Closed -> {
                        check(peers[event.player]?.accepted == true)
                        dedicatedCompletions++
                        val output = Path.of(requireNotNull(System.getProperty("strata.fabric.receipt")))
                        Files.writeString(output, "runId=$run\nconnections=$dedicatedConnections\ncompletions=$dedicatedCompletions\ninput=confirmed\napi=Strata\nserver=dedicated\n")
                    }

                    else -> {}
                }
            }
        }
    }

    /**
     * Opens the standard screen using caller-owned state inside the public factory.
     */
    public fun open(player: ServerPlayer) {
        check(peers.containsKey(player).not())
        val session = Strata.open(owner, player) { Verification().also { peers[player] = it }.definition() }
        checkNotNull(peers[player]).session = session
    }

    /**
     * Reports the production runtime's successful capability negotiation.
     */
    public fun ready(player: ServerPlayer): Boolean = Strata.capabilities(player) != null

    /**
     * Reports business input and acknowledged presentation on the server thread.
     */
    public fun accepted(player: ServerPlayer): Boolean = peers[player]?.let { it.accepted && it.session?.status is UiSessionStatus.Ready } == true

    /**
     * Reports the production runtime's terminal handle after client closure.
     */
    public fun closed(player: ServerPlayer): Boolean = peers[player]?.session?.status is UiSessionStatus.Closed

    /**
     * Retains only this test's authoritative state and public lifecycle handle.
     */
    private class Verification {
        var session: UiSession? = null
        var accepted = false
        private val field = TextFieldState(maxLength = 64)
        private val ticks = mutableStateOf(0)
        private val activated = mutableStateOf(false)

        fun definition(): UiDefinition =
            UiDefinition("Strata Fabric server verification") {
                Column(Modifier.Empty.background(ArgbColor(if (activated.value) -16776961 else -14671840))) {
                    TextField(field, IntSize(160, 20))
                    Button(
                        "Confirm",
                        160,
                        modifier =
                            Modifier.Empty.onActivate {
                                check(field.value.contentEquals("native-日本語"))
                                activated.value = true
                                accepted = true
                            },
                    )
                    Text("Updates: ${ticks.value}")
                    Canvas(RemoteCanvas.source(type, Unit) { ProjectionValue.Absent }, IntSize(16, 16))
                }
            }

        fun tick() {
            if (session?.status is UiSessionStatus.Closed) return
            ticks.value++
        }
    }
}
