package dev.s7a.strata.paper

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Application API supplied by the installed Strata Paper plugin.
 * Depend on Strata and compile against this API without bundling it.
 * Player operations require Paper's primary thread or the player's Folia region.
 * On Folia, create state inside [open] and enter [execute] for subsequent state or session access.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object PaperUi {
    @Volatile
    private var provider: PaperUiProvider? = null

    /**
     * Returns the current successful client negotiation, or null before readiness or after disconnection.
     */
    public fun capabilities(player: Player): UiClientCapabilities? = provider?.capabilities(player)

    /**
     * Registers a plugin-owned extension for future client negotiations.
     */
    public fun register(
        ownerPlugin: Plugin,
        type: ProjectionType,
    ) {
        require(ownerPlugin.isEnabled) { "The extension owner plugin must be enabled." }
        active().register(ownerPlugin, type)
    }

    /**
     * Opens the available definition after validating runtime and caller ownership.
     */
    internal fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: UiDefinition,
    ): UiSession = open(ownerPlugin, player) { definition }

    /**
     * Creates state and a fresh definition inside the player's execution owner, synchronously on the calling region.
     * Mutable state must be created inside this factory and outside the definition's reevaluated content.
     */
    public fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: () -> UiDefinition,
    ): UiSession {
        require(ownerPlugin.isEnabled) { "The UI owner plugin must be enabled." }
        return active().open(ownerPlugin, player, definition)
    }

    /**
     * Enters the player's UI execution owner for state and session access without changing the native region.
     * Async callers must first schedule on the player; this method never grants access to another entity's region.
     */
    public fun <T> execute(
        player: Player,
        operation: () -> T,
    ): T = active().execute(player, operation)

    /**
     * Installs one runtime; closing the registration releases only that installation.
     */
    @InternalStrataRuntimeApi
    public fun install(provider: PaperUiProvider): AutoCloseable =
        synchronized(this) {
            check(this.provider == null) { "A Paper UI runtime is already installed." }
            this.provider = provider
            var closed = false
            AutoCloseable {
                synchronized(this) {
                    if (closed.not()) {
                        closed = true
                        if (this.provider === provider) this.provider = null
                    }
                }
            }
        }

    private fun active(): PaperUiProvider = checkNotNull(provider) { "The Strata Paper plugin is not enabled." }
}
