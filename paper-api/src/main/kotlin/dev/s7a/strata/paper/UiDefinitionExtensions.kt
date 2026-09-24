package dev.s7a.strata.paper

import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Opens this definition on an authenticated client through the installed Strata Paper runtime.
 * Call on the primary thread; the enabled owner plugin bounds the session's lifetime.
 */
public fun UiDefinition.open(
    ownerPlugin: Plugin,
    player: Player,
): UiSession = PaperUi.open(ownerPlugin, player, this)
