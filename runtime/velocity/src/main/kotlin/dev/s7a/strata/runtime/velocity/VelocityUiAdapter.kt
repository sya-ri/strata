package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.toUiCapabilities
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.velocity.VelocityUiProvider

/**
 * Binds the application API to the existing bounded Velocity UI worker.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class VelocityUiAdapter(
    private val service: VelocityScreenService,
) : VelocityUiProvider {
    override fun open(
        ownerPlugin: Any,
        player: Player,
        definition: () -> UiDefinition,
    ) = service.openUi(ownerPlugin, player, definition)

    override fun capabilities(player: Player) = service.capabilities(player).thenApply { it?.toUiCapabilities() }

    override fun <T> execute(
        ownerPlugin: Any,
        operation: () -> T,
    ) = service.execute(ownerPlugin, operation)

    override fun register(
        ownerPlugin: Any,
        type: ProjectionType,
    ) = service.register(ownerPlugin, type)

    override fun release(ownerPlugin: Any) = service.release(ownerPlugin)
}
