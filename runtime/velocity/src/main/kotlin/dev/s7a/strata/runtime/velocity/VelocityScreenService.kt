package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.RemoteCapabilities
import dev.s7a.strata.runtime.remote.RemoteConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteFailure
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.runtime.remote.RemoteProtocolException
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.screen.ScreenDefinition
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Serializes Velocity declarations, state construction, and handlers on one dedicated UI thread.
 * API work is bounded independently of each player's bounded packet inbox; no action queue is silently coalesced.
 * Shutdown rejects new work, completes queued requests exceptionally, and releases the common host on its owner thread.
 */
@Suppress("TooManyFunctions") // Keeps the worker, API queue, player lifetime, and terminal cleanup under one owner.
internal class VelocityScreenService(
    private val proxy: ProxyServer,
    private val report: (Throwable) -> Unit,
) {
    private val lock = Any()
    private val commands = ArrayBlockingQueue<Command<*>>(1024)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "Strata Velocity UI").apply { isDaemon = true } }
    private val players = mutableMapOf<Player, ServerConnection?>()
    private val terminated = CompletableFuture<Unit>()
    private var stopping = false
    private val owner = executor.submit<Thread> { Thread.currentThread() }.get()
    private val host =
        executor
            .submit<RemoteScreenService<Player, Any>> {
                RemoteScreenService(
                    RemoteEndpoint.Proxy,
                    { player, bytes ->
                        if (player.sendPluginMessage(CHANNEL, bytes).not()) throw RemoteProtocolException(RemoteFailure.Disconnected, "Proxy transport is unavailable.")
                    },
                    report,
                    { operation -> if (Thread.currentThread() === owner) operation() else enqueue(operation) },
                )
            }.get()
    private val ticker =
        executor.scheduleWithFixedDelay({
            repeat(64) { commands.poll()?.run() }
            runCatching(host::tick).onFailure(report)
        }, 0, 50, TimeUnit.MILLISECONDS)

    /**
     * Records discovery even if it precedes the first backend's completed play connection.
     */
    fun discover(player: Player): CompletableFuture<Unit> =
        submit {
            if (player.isActive) {
                if (players.containsKey(player).not()) players[player] = null
                joinReady(player)
                discoverBackend(player)
            }
        }

    /**
     * Re-discovers the new backend and retires screens tied to its predecessor's native container.
     */
    fun connected(player: Player): CompletableFuture<Unit> =
        submit {
            if (player in players) {
                val current = player.currentServer.orElse(null)
                val previous = players[player]
                if (previous != null && previous !== current) host.containerChanged(player)
                joinReady(player)
                discoverBackend(player)
            }
        }

    /**
     * Copies authenticated proxy-bound frames without executing declaration or handler code.
     */
    fun enqueue(
        player: Player,
        bytes: ByteArray,
    ) {
        host.enqueue(player, bytes)
    }

    /**
     * Releases all player references, queued fragments, and authoritative state after disconnect.
     */
    fun disconnect(player: Player): CompletableFuture<Unit> =
        submit {
            players.remove(player)
            host.disconnect(player)
        }

    /**
     * Constructs the entire definition and its state on the UI owner thread.
     */
    fun open(
        owner: Any,
        player: Player,
        definition: () -> ScreenDefinition,
    ): CompletableFuture<RemoteScreenSession> =
        submit {
            requireOwner(owner)
            host.open(owner, player, definition())
        }

    /**
     * Returns an immutable successful negotiation snapshot, or null when unavailable.
     */
    fun capabilities(player: Player): CompletableFuture<RemoteCapabilities?> = submit { host.capabilities(player) }

    /**
     * Applies a plugin's external event or asynchronous result to its owner-thread UI state.
     */
    fun <T> execute(
        owner: Any,
        operation: () -> T,
    ): CompletableFuture<T> =
        submit {
            requireOwner(owner)
            operation()
        }

    /**
     * Registers exact extension capabilities before subsequent player handshakes.
     */
    fun register(
        owner: Any,
        type: ProjectionType,
    ): CompletableFuture<Unit> =
        submit {
            requireOwner(owner)
            host.register(owner, type)
        }

    /**
     * Withdraws an owner's extensions and closes every screen retaining its handlers or required schemas.
     */
    fun release(owner: Any): CompletableFuture<Unit> =
        submit {
            requireOwner(owner)
            host.ownerDisabled(owner)
        }

    /**
     * Completes after terminal owner-thread cleanup; safe to call repeatedly from shutdown events.
     */
    @Suppress("TooGenericExceptionCaught") // Every owner cleanup failure must complete the shutdown future and stop the worker.
    fun close(): CompletableFuture<Unit> =
        synchronized(lock) {
            if (stopping) return@synchronized terminated
            stopping = true
            ticker.cancel(false)
            executor.execute {
                try {
                    while (true) {
                        val command = commands.poll() ?: break
                        command.fail(RemoteProtocolException(RemoteFailure.OwnerClosed, "Velocity screens have stopped."))
                    }
                    host.close()
                    players.clear()
                    terminated.complete(Unit)
                } catch (failure: Throwable) {
                    terminated.completeExceptionally(failure)
                } finally {
                    executor.shutdown()
                }
            }
            terminated
        }

    private fun joinReady(player: Player) {
        val backend = player.currentServer.orElse(null) ?: return
        if (players[player] == null) {
            host.join(player)
            host.enqueue(player, RemotePacket.encode(RemotePacket.Discovery))
        }
        players[player] = backend
    }

    private fun discoverBackend(player: Player) {
        player.currentServer.ifPresent { backend ->
            if (backend.sendPluginMessage(CHANNEL, RemotePacket.encode(RemotePacket.Discovery)).not()) {
                throw RemoteProtocolException(RemoteFailure.Disconnected, "Backend discovery could not be delivered.")
            }
        }
    }

    private fun requireOwner(owner: Any) {
        require(proxy.pluginManager.fromInstance(owner).isPresent) { "The screen owner must be a loaded Velocity plugin instance." }
    }

    private fun <T> submit(operation: () -> T): CompletableFuture<T> = runCatching { enqueue(operation) }.getOrElse { CompletableFuture.failedFuture(it) }

    private fun <T> enqueue(operation: () -> T): CompletableFuture<T> =
        synchronized(lock) {
            if (stopping) throw RemoteProtocolException(RemoteFailure.OwnerClosed, "Velocity screens have stopped.")
            val command = Command(operation)
            if (commands.offer(command).not()) throw RemoteProtocolException(RemoteFailure.ResourceLimit, "Velocity UI request queue is full.")
            command.result
        }

    /**
     * Owns one queued callback only until execution or terminal rejection.
     */
    private class Command<T>(
        private val operation: () -> T,
    ) {
        val result = CompletableFuture<T>()

        fun run() {
            if (result.isCancelled) return
            runCatching(operation).fold(result::complete, result::completeExceptionally)
        }

        fun fail(failure: Throwable) {
            result.completeExceptionally(failure)
        }
    }

    /**
     * Native channel identity shared by registration and authenticated message routing.
     */
    companion object {
        val CHANNEL: MinecraftChannelIdentifier = MinecraftChannelIdentifier.from(RemoteConnection.CHANNEL)
    }
}
