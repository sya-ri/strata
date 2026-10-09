@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.toUiCapabilities
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import net.fabricmc.loader.api.ModContainer
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/**
 * Server-owned UI entry point for dedicated and integrated Fabric servers.
 * Operations require the server thread after Fabric emits SERVER_STARTED; async callers must schedule there first.
 * Create mutable state inside [open]'s factory and keep it on that server's execution owner.
 */
public object FabricServerUi {
    /**
     * Creates state and a definition on the server owner, returning its presentation handle.
     */
    public fun open(
        ownerMod: ModContainer,
        player: ServerPlayer,
        definition: () -> UiDefinition,
    ): UiSession =
        FabricServerUiServices
            .active(player)
            .service
            .open(ownerMod, player.connection, definition())
            .uiSession

    /**
     * Returns negotiated capabilities, or null before readiness or after disconnect.
     */
    public fun capabilities(player: ServerPlayer): UiClientCapabilities? =
        FabricServerUiServices
            .find(player)
            ?.service
            ?.capabilities(player.connection)
            ?.toUiCapabilities()

    /**
     * Executes synchronously on the calling server thread without scheduling asynchronous work.
     */
    public fun <T> execute(
        server: MinecraftServer,
        operation: () -> T,
    ): T {
        FabricServerUiServices.active(server)
        return operation()
    }

    /**
     * Registers a schema for future negotiations; existing connections must reconnect.
     */
    public fun register(
        server: MinecraftServer,
        ownerMod: ModContainer,
        projectionType: ProjectionType,
    ) {
        FabricServerUiServices.active(server).service.register(ownerMod, projectionType)
    }

    /**
     * Releases an owner's sessions, extension schemas, and notification subscriptions.
     */
    public fun release(
        server: MinecraftServer,
        ownerMod: ModContainer,
    ) {
        FabricServerUiServices.active(server).release(ownerMod)
    }

    /**
     * Subscribes to committed notifications on the server owner.
     * Close the subscription on that owner; [release] and server shutdown also remove it.
     * Listener failures are reported without rolling back a committed transition.
     */
    public fun listen(
        server: MinecraftServer,
        ownerMod: ModContainer,
        listener: (FabricServerUiEvent) -> Unit,
    ): AutoCloseable = FabricServerUiServices.active(server).listen(ownerMod, listener)
}
