package dev.s7a.strata.examples.velocity

import com.google.inject.Inject
import com.velocitypowered.api.command.SimpleCommand
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.plugin.Dependency
import com.velocitypowered.api.plugin.Plugin
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import dev.s7a.strata.velocity.VelocityUi
import dev.s7a.strata.velocity.event.StrataUiClosedEvent
import net.kyori.adventure.text.Component
import org.slf4j.Logger

/**
 * Installable external consumer of the public Velocity API; does not bundle Strata or require a Paper plugin.
 */
@Plugin(id = "strata-demo", name = "Strata Demo", dependencies = [Dependency(id = "strata")])
public class VelocityDemoPlugin
    @Inject
    constructor(
        private val proxy: ProxyServer,
        private val logger: Logger,
    ) {
        /**
         * Reports a rejected opening using detached event data; no UI-thread state is read here.
         */
        @Subscribe
        public fun uiClosed(event: StrataUiClosedEvent) {
            if (event.ownerPlugin === this && event.presentation == null) {
                event.player.sendMessage(Component.text("The screen could not open: ${event.reason}."))
            }
        }

        /**
         * Registers a proxy command whose callback creates UI state through the public owner-thread factory.
         */
        @Subscribe
        @Suppress("UnusedParameter") // Velocity requires the event argument for listener registration.
        public fun initialize(event: ProxyInitializeEvent) {
            val command =
                object : SimpleCommand {
                    override fun execute(invocation: SimpleCommand.Invocation) {
                        val player = invocation.source() as? Player ?: return
                        VelocityUi.open(this@VelocityDemoPlugin, player, VelocityDemoScreens::counter).whenComplete { _, failure ->
                            if (failure != null) {
                                logger.warn("The example screen could not open", failure)
                            }
                        }
                    }
                }
            val metadata =
                proxy.commandManager
                    .metaBuilder("strata-proxy-demo")
                    .plugin(this)
                    .build()
            proxy.commandManager.register(metadata, command)
        }
    }
