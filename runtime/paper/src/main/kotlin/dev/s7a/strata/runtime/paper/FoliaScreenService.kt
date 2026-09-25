@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.paper

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteCapabilities
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.RuntimeExecutionOwner
import dev.s7a.strata.ui.UiDefinition
import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap

/**
 * Gives each connected player an independent serial owner following Folia's entity scheduler.
 * Native player calls require its owning region. Network callbacks only enqueue bounded transport frames.
 * Plugin shutdown cancels tasks and releases detached UI resources without sending or touching world state.
 */
@Suppress("TooManyFunctions") // Keeps player admission, schema ownership, and terminal lifecycle at one platform boundary.
internal class FoliaScreenService(
    private val plugin: Plugin,
    private val ownsPlayer: (Player) -> Boolean = Bukkit::isOwnedByCurrentRegion,
    private val notify: (Player, RemoteLifecycleEvent<Plugin>) -> Unit = { player, event ->
        plugin.server.pluginManager.callEvent(paperUiEvent(player, event))
    },
) : AutoCloseable {
    private val peers = ConcurrentHashMap<Player, Peer>()
    private val extensions = mutableMapOf<ProjectionType, Plugin>()
    private val builtins = RemoteRegistry().also(RemoteBuiltins::register).types

    @Volatile
    private var closed = false

    /**
     * Starts one region-owned connection and ticker, including players present at plugin enable.
     */
    fun join(player: Player) {
        if (closed) return
        if (ownsPlayer(player).not()) {
            player.scheduler.run(plugin, { join(player) }, null)
            return
        }
        val types = synchronized(extensions) { extensions.toMap() }
        val peer = Peer(player, types)
        val previous =
            synchronized(peers) {
                if (closed) {
                    peer.close()
                    return
                }
                peers.put(player, peer)
            }
        previous?.close()
        peer.start()
    }

    /**
     * Receives authenticated bytes without invoking user code on the network callback.
     */
    fun enqueue(
        player: Player,
        bytes: ByteArray,
    ) {
        peers[player]?.enqueue(bytes)
    }

    /**
     * Creates state and its definition within this player's owner on the current entity region.
     */
    fun open(
        owner: Plugin,
        player: Player,
        definition: () -> UiDefinition,
    ): RemoteScreenSession =
        withService(player) { service ->
            require(owner.isEnabled) { "The screen owner plugin must be enabled." }
            service.open(owner, player, definition())
        }

    /**
     * Reads current negotiation from the player's entity region.
     */
    fun capabilities(player: Player): RemoteCapabilities? {
        checkRegion(player)
        return peers[player]?.run { it.capabilities(player) }
    }

    /**
     * Enters a player's serial UI ownership for externally driven state changes.
     * The caller must already be on that player's region; this method never schedules or blocks on another region.
     */
    fun <T> execute(
        player: Player,
        operation: () -> T,
    ): T = withService(player) { _ -> operation() }

    /**
     * Publishes a schema snapshot for future player connections; existing handshakes remain unchanged.
     */
    fun register(
        owner: Plugin,
        type: ProjectionType,
    ) {
        synchronized(extensions) {
            check(closed.not()) { "The Strata plugin is not enabled." }
            require(owner.isEnabled) { "The extension owner plugin must be enabled." }
            require((type in builtins).not() && (type in extensions).not()) { "A remote schema is already registered: $type." }
            extensions[type] = owner
        }
    }

    /**
     * Withdraws schemas immediately; each player's next owned operation retires affected screens before dispatch.
     */
    fun ownerDisabled(owner: Plugin) {
        synchronized(extensions) { extensions.entries.removeAll { it.value === owner } }
        peers.values.forEach { it.invalidated.add(owner) }
    }

    /**
     * Removes the disconnected incarnation and releases its queues and state exactly once.
     */
    fun disconnect(player: Player) {
        checkRegion(player)
        peers.remove(player)?.disconnect()
    }

    /**
     * Retires screen operations when a native inventory is replaced or closed.
     */
    fun containerChanged(player: Player) {
        checkRegion(player)
        peers[player]?.run { it.containerChanged(player) }
    }

    override fun close() {
        val remaining =
            synchronized(peers) {
                closed = true
                peers.values.toList().also { peers.clear() }
            }
        synchronized(extensions) { extensions.clear() }
        remaining.forEach(Peer::close)
    }

    private fun <T> withService(
        player: Player,
        operation: (RemoteScreenService<Player, Plugin>) -> T,
    ): T {
        checkRegion(player)
        return checkNotNull(peers[player]) { "The player has no active Strata connection." }.run(operation)
    }

    private fun checkRegion(player: Player) {
        check(ownsPlayer(player)) { "Paper screens require the player's owning region." }
    }

    /**
     * A single player's transport, retained state, and cancellable entity task.
     * The lock serializes terminal shutdown with an in-flight region tick; callbacks never acquire another player's lock.
     */
    private inner class Peer(
        private val player: Player,
        types: Map<ProjectionType, Plugin>,
    ) : AutoCloseable {
        private val lock = Any()
        private val owner = RuntimeExecutionOwner()
        private var closed = false
        private var ticker: ScheduledTask? = null
        val invalidated: MutableSet<Plugin> = ConcurrentHashMap.newKeySet()
        private val registeredOwners = types.values.toMutableSet()
        private val service =
            owner.run {
                RemoteScreenService(
                    RemoteEndpoint.Server,
                    { recipient, bytes -> recipient.sendPluginMessage(plugin, RemoteConnection.CHANNEL, bytes) },
                    { failure -> plugin.logger.warning("Strata screen ended: ${failure.message}") },
                    { operation ->
                        checkRegion(player)
                        run { operation() }
                    },
                    notify = notify,
                ).also { remote ->
                    types.forEach { (type, pluginOwner) -> remote.register(pluginOwner, type) }
                    remote.join(player)
                }
            }

        /**
         * Schedules ticks on the moving entity owner and handles already-retired entities without retaining them.
         */
        fun start() {
            synchronized(lock) {
                if (closed) return
                ticker =
                    player.scheduler.runAtFixedRate(
                        plugin,
                        { tick() },
                        { retire() },
                        1L,
                        1L,
                    )
                if (ticker == null) retire()
            }
        }

        /**
         * The common inbox handles the race with terminal close without running UI work.
         */
        fun enqueue(bytes: ByteArray) {
            service.enqueue(player, bytes)
        }

        /**
         * Applies pending plugin invalidations before any state access or input dispatch.
         */
        fun <T> run(operation: (RemoteScreenService<Player, Plugin>) -> T): T =
            synchronized(lock) {
                check(closed.not()) { "The player's Strata connection is closed." }
                owner.run {
                    registeredOwners.filter { it.isEnabled.not() }.forEach(invalidated::add)
                    invalidated.toList().forEach { disabled ->
                        invalidated.remove(disabled)
                        registeredOwners.remove(disabled)
                        service.ownerDisabled(disabled)
                    }
                    operation(service)
                }
            }

        /**
         * Reports a disconnected player before terminal cleanup cancels its ticker and releases the owner.
         */
        fun disconnect() {
            synchronized(lock) {
                if (closed) return
                owner.run { service.disconnect(player) }
                close()
            }
        }

        override fun close() {
            synchronized(lock) {
                if (closed) return
                closed = true
                ticker?.cancel()
                ticker = null
                invalidated.clear()
                registeredOwners.clear()
                owner.run { service.close() }
            }
        }

        private fun tick() {
            synchronized(lock) {
                if (closed) return
                checkRegion(player)
                run { it.tick() }
            }
        }

        private fun retire() {
            peers.remove(player, this)
            disconnect()
        }
    }
}
