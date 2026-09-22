package dev.s7a.strata.integration.velocity

import com.google.inject.Inject
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.connection.DisconnectEvent
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.plugin.annotation.DataDirectory
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import dev.s7a.strata.runtime.velocity.VelocityScreens
import org.slf4j.Logger
import java.nio.file.Path
import java.util.UUID

/**
 * External public-API fixture for a real proxy's input, owner thread, and backend-switch lifecycle.
 * Commands never construct or touch mutable UI state outside the Strata worker.
 */
@Plugin(id = "strata-acceptance", name = "Strata Velocity Acceptance", dependencies = [Dependency(id = "strata")])
public class VelocityAcceptancePlugin
    @Inject
    constructor(
        private val proxy: ProxyServer,
        private val logger: Logger,
        @DataDirectory private val directory: Path,
    ) {
        private val sessions = mutableMapOf<UUID, VelocityAcceptanceSession>()

        /**
         * Installs two explicit commands; the runner alone starts the acceptance transaction.
         */
        @Subscribe
        @Suppress("UnusedParameter") // Velocity requires the event argument to register this listener.
        public fun initialize(event: ProxyInitializeEvent) {
            register("strata-proxy-verify") { player ->
                VelocityScreens
                    .open(this, player) {
                        sessions.remove(player.uniqueId)?.close()
                        VelocityAcceptanceSession(this, proxy, player, directory).also { sessions[player.uniqueId] = it }.controls()
                    }.thenCompose { handle ->
                        VelocityScreens.execute(this) { checkNotNull(sessions[player.uniqueId]).bind(handle) }
                    }.exceptionally {
                        logger.error("Velocity acceptance could not start", it)
                    }
            }
            register("strata-proxy-resume") { player ->
                VelocityScreens
                    .open(this, player) { checkNotNull(sessions[player.uniqueId]).resume() }
                    .thenCompose { handle ->
                        VelocityScreens.execute(this) { checkNotNull(sessions[player.uniqueId]).bind(handle) }
                    }.exceptionally {
                        logger.error("Velocity acceptance could not resume", it)
                    }
            }
        }

        /**
         * Drops the fixture's own references in addition to the runtime's automatic player cleanup.
         */
        @Subscribe
        public fun disconnected(event: DisconnectEvent) {
            VelocityScreens.execute(this) { sessions.remove(event.player.uniqueId)?.close() }
        }

        private fun register(
            name: String,
            action: (Player) -> Unit,
        ) {
            val command =
                SimpleCommand { invocation ->
                    (invocation.source() as? Player)?.let(action)
                }
            proxy.commandManager.register(
                proxy.commandManager
                    .metaBuilder(name)
                    .plugin(this)
                    .build(),
                command,
            )
        }
    }
