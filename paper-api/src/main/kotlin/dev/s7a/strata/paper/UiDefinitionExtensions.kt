package dev.s7a.strata.paper

import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Opens this definition on an authenticated client through the installed Strata Paper runtime.
 * Call on Paper's primary thread or the player's Folia region; the enabled plugin bounds the lifetime.
 * Captured mutable state must belong to the player's UI owner; [PaperUi.open] provides a factory for creating it.
 */
public fun UiDefinition.open(
    ownerPlugin: Plugin,
    player: Player,
): UiSession = PaperUi.open(ownerPlugin, player, this)
