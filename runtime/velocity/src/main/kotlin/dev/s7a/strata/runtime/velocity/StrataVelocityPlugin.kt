@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.EventTask
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.event.player.ServerPostConnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import dev.s7a.strata.runtime.remote.RemoteEndpoint
import dev.s7a.strata.runtime.remote.RemotePacket
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.velocity.VelocityUi
import org.slf4j.Logger

/**
 * Installable Velocity endpoint with authenticated proxy/backend routing and an owned UI worker.
 * Backend messages may use only the server route; client proxy actions are never forwarded to a backend.
 */
@Plugin(id = "strata", name = "Strata")
public class StrataVelocityPlugin
    @Inject
    constructor(
        private val proxy: ProxyServer,
        private val logger: Logger,
    ) {
        private var uiRegistration: AutoCloseable? = null
        private val routingLock = Any()

        @Volatile
        private var screens: VelocityScreenService? = null

        /**
         * Registers the native channel only after Velocity's plugin services are initialized.
         */
        @Subscribe
        @Suppress("UnusedParameter") // Velocity requires the event argument for listener registration.
        public fun initialize(event: ProxyInitializeEvent) {
            val service = VelocityScreenService(proxy) { logger.warn("Strata Velocity screen ended", it) }
            screens = service
            VelocityScreens.install(service)
            uiRegistration = VelocityUi.install(VelocityUiAdapter(service))
            proxy.channelRegistrar.register(VelocityScreenService.CHANNEL)
        }

        /**
         * Classifies the authenticated sender and submits its native write under one routing lock.
         * Handling the event prevents other asynchronous listeners from forwarding retired backend packets later.
         */
        @Subscribe(async = false)
        public fun message(event: PluginMessageEvent): EventTask? =
            synchronized(routingLock) {
                if (event.identifier != VelocityScreenService.CHANNEL || event.result.isAllowed.not()) return@synchronized null
                event.result = PluginMessageEvent.ForwardResult.handled()
                val service = screens ?: return@synchronized null
                val bytes = event.data
                val packet =
                    runCatching { RemotePacket.decode(bytes) }.getOrElse {
                        (event.source as? Player)?.let { player -> service.enqueue(player, byteArrayOf()) }
                        return@synchronized null
                    }
                when (val source = event.source) {
                    is Player -> {
                        when (packet) {
                            RemotePacket.Discovery -> {
                                return@synchronized EventTask.resumeWhenComplete(service.discover(source))
                            }

                            is RemotePacket.Frame -> {
                                when (packet.address.endpoint) {
                                    RemoteEndpoint.Server -> source.currentServer.ifPresent { it.sendPluginMessage(VelocityScreenService.CHANNEL, bytes) }
                                    RemoteEndpoint.Proxy -> service.enqueue(source, bytes)
                                }
                            }
                        }
                    }

                    is ServerConnection -> {
                        if (packet is RemotePacket.Frame && packet.address.endpoint == RemoteEndpoint.Server && source.player.currentServer.orElse(null) === source) {
                            source.player.sendPluginMessage(VelocityScreenService.CHANNEL, bytes)
                        }
                    }
                }
                null
            }

        /**
         * Re-discovers Paper on the new backend and releases the proxy screen's previous native-container binding.
         */
        @Subscribe
        public fun connected(event: ServerPostConnectEvent): EventTask? = screens?.connected(event.player)?.let(EventTask::resumeWhenComplete)

        /**
         * Releases player transports and state even when the disconnect interrupts negotiation.
         */
        @Subscribe
        public fun disconnected(event: DisconnectEvent): EventTask? = screens?.disconnect(event.player)?.let(EventTask::resumeWhenComplete)

        /**
         * Waits for owner-thread cleanup before Velocity exits, then releases the channel registration.
         */
        @Subscribe
        @Suppress("UnusedParameter") // Velocity awaits this event while terminal cleanup completes.
        public fun shutdown(event: ProxyShutdownEvent): EventTask? {
            VelocityScreens.install(null)
            uiRegistration?.close()
            uiRegistration = null
            val previous = screens
            screens = null
            proxy.channelRegistrar.unregister(VelocityScreenService.CHANNEL)
            return previous?.close()?.let(EventTask::resumeWhenComplete)
        }
    }
