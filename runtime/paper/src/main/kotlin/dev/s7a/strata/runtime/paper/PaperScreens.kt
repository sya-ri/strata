package dev.s7a.strata.runtime.paper

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.RemoteCapabilities
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.screen.ScreenDefinition
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Public entry point supplied by the installed Strata plugin.
 * Consumers declare a plugin dependency on Strata and invoke this API on Paper's primary server thread.
 * The owner plugin controls the screen lifetime; its disable event closes every screen it owns.
 */
public object PaperScreens {
    private var service: RemoteScreenService<Player, Plugin>? = null

    /**
     * Consumes one screen definition and replaces the player's previous Strata screen.
     * Unavailable clients and unsupported declarations return a terminal handle with a typed reason.
     */
    public fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: ScreenDefinition,
    ): RemoteScreenSession {
        checkThread()
        require(ownerPlugin.isEnabled) { "The screen owner plugin must be enabled." }
        return checkNotNull(service) { "The Strata plugin is not enabled." }.open(ownerPlugin, player, definition)
    }

    /**
     * Returns negotiated client schemas and limits, or null before negotiation or after disconnect.
     */
    public fun capabilities(player: Player): RemoteCapabilities? {
        checkThread()
        return service?.capabilities(player)
    }

    /**
     * Registers a plugin-owned component, modifier, Canvas renderer, or semantics role schema before player negotiation.
     * Declaration projections supply typed encoders and handlers; clients separately install matching decoders and factories.
     * Existing connections retain their negotiated snapshot and must reconnect to use newly registered types.
     * Disabling the owner withdraws its types and closes screens that require them.
     */
    public fun register(
        ownerPlugin: Plugin,
        type: ProjectionType,
    ) {
        checkThread()
        require(ownerPlugin.isEnabled) { "The extension owner plugin must be enabled." }
        checkNotNull(service) { "The Strata plugin is not enabled." }.register(ownerPlugin, type)
    }

    /**
     * Binds the enabled plugin service, or releases it before plugin shutdown.
     */
    internal fun install(service: RemoteScreenService<Player, Plugin>?) {
        checkThread()
        check(this.service == null || service == null) { "A Strata service is already installed." }
        this.service = service
    }

    /**
     * Rejects access outside Paper's supported primary-thread ownership contract.
     */
    internal fun checkThread() {
        check(Bukkit.isPrimaryThread()) { "Paper screens require the primary server thread." }
    }
}
