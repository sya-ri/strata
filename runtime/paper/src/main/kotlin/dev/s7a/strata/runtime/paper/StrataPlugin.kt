package dev.s7a.strata.runtime.paper

import dev.s7a.strata.runtime.remote.RemoteConnection
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.plugin.messaging.PluginMessageListener
import org.bukkit.scheduler.BukkitTask

/**
 * Installable Paper plugin owning messaging registrations and primary-thread UI scheduling.
 * Other plugins use [PaperScreens] and declare Strata as a dependency.
 */
public class StrataPlugin :
    JavaPlugin(),
    Listener,
    PluginMessageListener {
    private var screens: PaperScreenService? = null
    private var ticker: BukkitTask? = null

    override fun onEnable() {
        val service = PaperScreenService(this)
        screens = service
        PaperScreens.install(service)
        server.messenger.registerOutgoingPluginChannel(this, RemoteConnection.CHANNEL)
        server.messenger.registerIncomingPluginChannel(this, RemoteConnection.CHANNEL, this)
        server.pluginManager.registerEvents(this, this)
        ticker = server.scheduler.runTaskTimer(this, Runnable(service::tick), 1L, 1L)
        server.onlinePlayers.forEach(service::join)
    }

    override fun onDisable() {
        PaperScreens.install(null)
        ticker?.cancel()
        ticker = null
        val service = screens
        screens = null
        service?.close()
        HandlerList.unregisterAll(this as Listener)
        server.messenger.unregisterIncomingPluginChannel(this)
        server.messenger.unregisterOutgoingPluginChannel(this)
    }

    override fun onPluginMessageReceived(
        channel: String,
        player: Player,
        message: ByteArray,
    ) {
        if (channel == RemoteConnection.CHANNEL) screens?.enqueue(player, message)
    }

    /**
     * Starts negotiation only after a player has joined the server.
     */
    @EventHandler
    public fun onJoin(event: PlayerJoinEvent) {
        screens?.join(event.player)
    }

    /**
     * Releases the departed player's screen and all transport buffers.
     */
    @EventHandler
    public fun onQuit(event: PlayerQuitEvent) {
        screens?.disconnect(event.player)
    }

    /**
     * Ends dependent-plugin screens before their handlers become unavailable.
     */
    @EventHandler
    public fun onPluginDisable(event: PluginDisableEvent) {
        screens?.ownerDisabled(event.plugin)
    }

    /**
     * Retires the prior screen after an accepted native inventory replacement.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onInventoryOpen(event: InventoryOpenEvent) {
        (event.player as? Player)?.let { screens?.containerChanged(it) }
    }

    /**
     * Prevents a screen from retaining operations for a container that has been closed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public fun onInventoryClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let { screens?.containerChanged(it) }
    }
}
