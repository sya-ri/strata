package dev.s7a.strata.paper

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Application API supplied by the installed Strata Paper plugin.
 * Depend on Strata and compile against this API without bundling it.
 * Definitions, state, session operations, and events use Paper's primary thread.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object PaperUi {
    private var provider: PaperUiProvider? = null

    /**
     * Returns the current successful client negotiation, or null before readiness or after disconnection.
     */
    public fun capabilities(player: Player): UiClientCapabilities? {
        checkThread()
        return provider?.capabilities(player)
    }

    /**
     * Registers a plugin-owned extension for future client negotiations.
     */
    public fun register(
        ownerPlugin: Plugin,
        type: ProjectionType,
    ) {
        checkThread()
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
    ): UiSession {
        checkThread()
        require(ownerPlugin.isEnabled) { "The UI owner plugin must be enabled." }
        return active().open(ownerPlugin, player, definition)
    }

    /**
     * Installs one runtime; closing the registration releases only that installation.
     */
    @InternalStrataRuntimeApi
    public fun install(provider: PaperUiProvider): AutoCloseable {
        checkThread()
        check(this.provider == null) { "A Paper UI runtime is already installed." }
        this.provider = provider
        var closed = false
        return AutoCloseable {
            checkThread()
            if (closed.not()) {
                closed = true
                if (this.provider === provider) this.provider = null
            }
        }
    }

    private fun active(): PaperUiProvider = checkNotNull(provider) { "The Strata Paper plugin is not enabled." }

    private fun checkThread() {
        check(Bukkit.isPrimaryThread()) { "Paper UIs require the primary server thread." }
    }
}
