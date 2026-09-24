package dev.s7a.strata.velocity

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import java.util.concurrent.CompletableFuture

/**
 * Installed runtime boundary that queues operations on the dedicated UI thread.
 */
@InternalStrataRuntimeApi
public interface VelocityUiProvider {
    /**
     * Constructs a definition and its state on the UI thread before transferring ownership.
     */
    public fun open(
        ownerPlugin: Any,
        player: Player,
        definition: () -> UiDefinition,
    ): CompletableFuture<UiSession>

    /**
     * Returns detached negotiated support, or null while unavailable.
     */
    public fun capabilities(player: Player): CompletableFuture<UiClientCapabilities?>

    /**
     * Queues short state or session work without blocking an event thread.
     */
    public fun <T> execute(
        ownerPlugin: Any,
        operation: () -> T,
    ): CompletableFuture<T>

    /**
     * Registers a plugin-owned exact projection schema for subsequent negotiations.
     */
    public fun register(
        ownerPlugin: Any,
        type: ProjectionType,
    ): CompletableFuture<Unit>

    /**
     * Closes a plugin's sessions and withdraws its projection schemas.
     */
    public fun release(ownerPlugin: Any): CompletableFuture<Unit>
}
