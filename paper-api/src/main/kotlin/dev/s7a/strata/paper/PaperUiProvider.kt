package dev.s7a.strata.paper

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Installed runtime boundary. Every call runs on Paper's primary thread.
 */
@InternalStrataRuntimeApi
public interface PaperUiProvider {
    /**
     * Transfers one available definition into a plugin-owned session.
     */
    public fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: UiDefinition,
    ): UiSession

    /**
     * Returns detached negotiated support, or null while unavailable.
     */
    public fun capabilities(player: Player): UiClientCapabilities?

    /**
     * Registers an exact plugin-owned projection schema for subsequent negotiations.
     */
    public fun register(
        ownerPlugin: Plugin,
        type: ProjectionType,
    )
}
