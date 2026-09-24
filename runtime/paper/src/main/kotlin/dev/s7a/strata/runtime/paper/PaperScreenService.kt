package dev.s7a.strata.runtime.paper

import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteScreenService
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Binds the common host to Paper's authenticated plugin messaging and primary-thread lifetime.
 */
internal fun paperScreenService(plugin: Plugin): RemoteScreenService<Player, Plugin> =
    RemoteScreenService(
        RemoteEndpoint.Server,
        { player, bytes -> player.sendPluginMessage(plugin, RemoteConnection.CHANNEL, bytes) },
        { failure -> plugin.logger.warning("Strata screen ended: ${failure.message}") },
        notify = { player, event -> plugin.server.pluginManager.callEvent(paperUiEvent(player, event)) },
    )
