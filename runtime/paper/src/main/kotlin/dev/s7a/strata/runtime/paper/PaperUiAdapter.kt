package dev.s7a.strata.runtime.paper

import dev.s7a.strata.paper.PaperUiProvider
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.toUiCapabilities
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Binds the application API to the installed authenticated Paper service.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class PaperUiAdapter : PaperUiProvider {
    override fun open(
        ownerPlugin: Plugin,
        player: Player,
        definition: () -> UiDefinition,
    ) = PaperScreens.openUi(ownerPlugin, player, definition).uiSession

    override fun <T> execute(
        player: Player,
        operation: () -> T,
    ): T = PaperScreens.execute(player, operation)

    override fun capabilities(player: Player) = PaperScreens.capabilities(player)?.toUiCapabilities()

    override fun register(
        ownerPlugin: Plugin,
        type: ProjectionType,
    ) = PaperScreens.register(ownerPlugin, type)
}
