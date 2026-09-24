package dev.s7a.strata.runtime.paper

import dev.s7a.strata.paper.PaperUiProvider
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.RemoteScreenService
import dev.s7a.strata.runtime.remote.toUiCapabilities
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Binds the application API to the installed authenticated Paper service.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PaperUiAdapter(
    private val service: RemoteScreenService<Player, Plugin>,
) : PaperUiProvider {
    override fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: UiDefinition,
    ) = service.open(ownerPlugin, player, definition).uiSession

    override fun capabilities(player: Player) = service.capabilities(player)?.toUiCapabilities()

    override fun register(
        ownerPlugin: Plugin,
        type: ProjectionType,
    ) = service.register(ownerPlugin, type)
}
