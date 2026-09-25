package dev.s7a.strata.paper

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Installed runtime boundary. The provider validates native ownership before evaluating factories or operations.
 * Player calls require Paper's primary thread or the player's Folia region; execution-owner entry belongs here.
 */
@InternalStrataRuntimeApi
public interface PaperUiProvider {
    /**
     * Creates and transfers one definition under the player's execution owner after validating native access.
     */
    public fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: () -> UiDefinition,
    ): UiSession

    /**
     * Enters an already-owned player's UI context without scheduling work or granting native region ownership.
     */
    public fun <T> execute(
        player: Player,
        operation: () -> T,
    ): T

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
