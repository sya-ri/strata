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
 * Consumers declare a plugin dependency on Strata and invoke player operations on Paper's primary thread or the player's Folia region.
 * The owner plugin controls the screen lifetime; its disable event closes every screen it owns.
 */
public object PaperScreens {
    @Volatile
    private var service: RemoteScreenService<Player, Plugin>? = null

    @Volatile
    private var folia: FoliaScreenService? = null

    /**
     * Consumes one screen definition and replaces the player's previous Strata screen.
     * Unavailable clients and unsupported declarations return a terminal handle with a typed reason.
     * On Folia, create captured mutable state through [execute], or use the definition-factory overload.
     */
    public fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: ScreenDefinition,
    ): RemoteScreenSession {
        val regionService = folia
        if (regionService != null) return regionService.open(ownerPlugin, player) { definition }
        checkThread()
        require(ownerPlugin.isEnabled) { "The screen owner plugin must be enabled." }
        return checkNotNull(service) { "The Strata plugin is not enabled." }.open(ownerPlugin, player, definition)
    }

    /**
     * Creates state and a definition within the player's UI owner, preserving it across Folia region migrations.
     * Call on the player's region (or Paper's primary thread), and construct mutable UI state inside [definition].
     * The factory runs synchronously once, before retained screen evaluation; handlers run on that player's region.
     */
    public fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: () -> ScreenDefinition,
    ): RemoteScreenSession {
        val regionService = folia
        if (regionService != null) return regionService.open(ownerPlugin, player, definition)
        checkThread()
        require(ownerPlugin.isEnabled) { "The screen owner plugin must be enabled." }
        checkNotNull(service) { "The Strata plugin is not enabled." }
        return open(ownerPlugin, player, definition())
    }

    /**
     * Updates or reads caller-retained UI state under the player's owner from an existing entity-region callback.
     * This call is synchronous and does not schedule work; asynchronous callers must first use the player's scheduler.
     * Do not share owner-confined UI state between players or capture it from another physical thread.
     */
    public fun <T> execute(
        player: Player,
        operation: () -> T,
    ): T {
        val regionService = folia
        if (regionService != null) return regionService.execute(player, operation)
        checkThread()
        checkNotNull(service) { "The Strata plugin is not enabled." }
        return operation()
    }

    /**
     * Returns negotiated client schemas and limits, or null before negotiation or after disconnect.
     */
    public fun capabilities(player: Player): RemoteCapabilities? {
        val regionService = folia
        if (regionService != null) return regionService.capabilities(player)
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
        val regionService = folia
        if (regionService != null) {
            regionService.register(ownerPlugin, type)
            return
        }
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
     * Publishes the Folia service during plugin lifecycle; native region entry is validated by that service.
     */
    internal fun installFolia(service: FoliaScreenService?) {
        check(folia == null || service == null) { "A Strata Folia service is already installed." }
        folia = service
    }

    /**
     * Rejects access outside Paper's supported primary-thread ownership contract.
     */
    internal fun checkThread() {
        check(Bukkit.isPrimaryThread()) { "Paper screens require the primary server thread." }
    }
}
