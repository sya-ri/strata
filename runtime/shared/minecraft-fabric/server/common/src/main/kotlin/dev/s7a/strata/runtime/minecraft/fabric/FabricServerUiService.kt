package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemoteLifecycleEvent
import dev.s7a.strata.runtime.remote.RemoteScreenService
import net.fabricmc.loader.api.ModContainer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerGamePacketListenerImpl
import net.minecraft.world.inventory.AbstractContainerMenu
import org.slf4j.LoggerFactory

/**
 * Owns one logical server's authenticated play handlers and disposable subscriptions.
 * Handler identity survives native player replacement; retained menu identities retire affected presentations before queued input.
 */
internal class FabricServerUiService(
    private val server: MinecraftServer,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(FabricServerUiService::class.java)
    private val menus = mutableMapOf<ServerGamePacketListenerImpl, AbstractContainerMenu>()
    private val listeners = mutableListOf<Subscription>()

    /**
     * Shared authoritative protocol/session owner for this logical server.
     */
    @get:JvmSynthetic
    val service: RemoteScreenService<ServerGamePacketListenerImpl, ModContainer> =
        RemoteScreenService(
            RemoteEndpoint.Server,
            FabricRemoteServerTransport::send,
            { logger.warn("Strata server UI ended", it) },
            notify = ::notify,
        )

    /**
     * Rejects access outside the native server thread.
     */
    @JvmSynthetic
    fun checkThread() {
        check(server.isSameThread) { "Fabric server UI requires the server thread." }
    }

    /**
     * Starts a fresh connection without retaining a previous player incarnation.
     */
    @JvmSynthetic
    fun join(player: ServerGamePacketListenerImpl) {
        checkThread()
        service.join(player)
        menus[player] = player.player.containerMenu
    }

    /**
     * Releases the disconnected player's transport and container identity.
     */
    @JvmSynthetic
    fun disconnect(player: ServerGamePacketListenerImpl) {
        checkThread()
        menus.remove(player)
        service.disconnect(player)
    }

    /**
     * Retires replaced native containers before admitting queued actions.
     */
    @JvmSynthetic
    fun tick() {
        checkThread()
        menus.keys.toList().forEach(::containerChanged)
        service.tick()
    }

    /**
     * Detects accepted replacement only; repeated hooks do not retire independent HUDs.
     */
    @JvmSynthetic
    fun containerChanged(player: ServerGamePacketListenerImpl) {
        checkThread()
        val current = player.player.containerMenu
        if (menus.put(player, current) !== current) service.containerChanged(player)
    }

    /**
     * Installs a subscription with owner-thread removal and terminal release.
     */
    @JvmSynthetic
    fun listen(
        owner: ModContainer,
        listener: (FabricServerUiEvent) -> Unit,
    ): AutoCloseable {
        checkThread()
        val subscription =
            Subscription(owner, listener) { retired ->
                checkThread()
                listeners.remove(retired)
            }
        listeners.add(subscription)
        return subscription
    }

    /**
     * Closes owned sessions before removing the owner's notification callbacks.
     */
    @JvmSynthetic
    fun release(owner: ModContainer) {
        checkThread()
        service.ownerDisabled(owner)
        listeners.filter { it.owner === owner }.forEach(Subscription::close)
    }

    @JvmSynthetic
    override fun close() {
        checkThread()
        try {
            service.close()
        } finally {
            menus.clear()
            listeners.toList().forEach(Subscription::close)
            listeners.clear()
        }
    }

    /**
     * Translates committed shared notifications on this logical server owner.
     */
    @JvmSynthetic
    fun notify(
        player: ServerGamePacketListenerImpl,
        event: RemoteLifecycleEvent<ModContainer>,
    ) {
        val notification =
            when (event) {
                is RemoteLifecycleEvent.Ready -> FabricServerUiEvent.Ready(player.player, event.capabilities)
                is RemoteLifecycleEvent.Disconnected -> FabricServerUiEvent.Disconnected(player.player, event.reason)
                is RemoteLifecycleEvent.Opened -> FabricServerUiEvent.Opened(player.player, event.owner, event.identity, event.session, event.presentation, event.category)
                is RemoteLifecycleEvent.PresentationChanged -> FabricServerUiEvent.PresentationChanged(player.player, event.owner, event.identity, event.session, event.previous, event.presentation, event.category)
                is RemoteLifecycleEvent.Closed -> FabricServerUiEvent.Closed(player.player, event.owner, event.identity, event.session, event.presentation, event.category, event.reason)
            }
        listeners.toList().forEach { subscription ->
            if (subscription in listeners) runCatching { subscription.listener?.invoke(notification) }.onFailure { logger.warn("Strata server UI listener failed", it) }
        }
    }

    /**
     * One owner-scoped callback, retained only until explicit or terminal release.
     */
    private class Subscription(
        val owner: ModContainer,
        var listener: ((FabricServerUiEvent) -> Unit)?,
        private var remove: ((Subscription) -> Unit)?,
    ) : AutoCloseable {
        override fun close() {
            remove?.invoke(this)
            remove = null
            listener = null
        }
    }
}
