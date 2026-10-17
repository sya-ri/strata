package dev.s7a.strata.runtime.minecraft.fabric

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import java.util.concurrent.ConcurrentHashMap

/**
 * Routes Fabric callbacks without retaining servers or players after terminal shutdown.
 */
internal object FabricServerUiServices {
    private val servers = ConcurrentHashMap<MinecraftServer, FabricServerUiService>()
    private val players = ConcurrentHashMap<ServerGamePacketListenerImpl, FabricServerUiService>()

    /**
     * Registers process-lifetime callbacks once from the common Mod initializer.
     */
    @JvmSynthetic
    fun initialize() {
        ServerLifecycleEvents.SERVER_STARTING.register { server ->
            check(servers.putIfAbsent(server, FabricServerUiService(server)) == null)
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val service = active(server)
            service.join(handler)
            players[handler] = service
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            players.remove(handler)?.let { service -> server.execute { if (servers[server] === service) service.disconnect(handler) } }
        }
        ServerTickEvents.END_SERVER_TICK.register { server -> servers[server]?.tick() }
        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            val service = servers.remove(server)
            if (service != null) {
                players.entries.removeIf { it.value === service }
                service.close()
            }
        }
    }

    /**
     * Resolves a logical server and checks its execution ownership.
     */
    @JvmSynthetic
    fun active(server: MinecraftServer): FabricServerUiService = checkNotNull(servers[server]) { "Strata server runtime is not active." }.also { it.checkThread() }

    /**
     * Finds a live authenticated connection, validating its execution owner when present.
     */
    @JvmSynthetic
    fun find(player: ServerPlayer): FabricServerUiService? = players[player.connection]?.takeIf { player.connection.player === player }?.also { it.checkThread() }

    /**
     * Resolves the authenticated connection without using caller-supplied player identifiers.
     */
    @JvmSynthetic
    fun active(player: ServerPlayer): FabricServerUiService = checkNotNull(find(player)) { "Player has no active Strata server connection." }

    /**
     * Retires a changed native container on the authenticated server execution owner.
     */
    fun containerChanged(player: ServerPlayer) {
        find(player)?.containerChanged(player.connection)
    }

    /**
     * Copies authenticated inbound bytes without dispatching application handlers.
     */
    fun enqueue(
        player: ServerPlayer,
        bytes: ByteArray,
    ) {
        players[player.connection]?.takeIf { player.connection.player === player }?.service?.enqueue(player.connection, bytes)
    }
}
