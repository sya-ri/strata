@file:Suppress("DEPRECATION") // Compatibility entry delegates to the common UI service.

package dev.s7a.strata.runtime.velocity

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.runtime.remote.RemoteCapabilities
import dev.s7a.strata.runtime.remote.RemoteScreenSession
import dev.s7a.strata.screen.ScreenDefinition
import java.util.concurrent.CompletableFuture

/**
 * Public entry point supplied by the installed Strata Velocity plugin.
 * Consumers depend on plugin ID `strata`; definitions, state, and handlers run on its dedicated UI thread.
 * Calls return immediately. Never join their futures from a screen handler or run blocking work in completion callbacks.
 */
public object VelocityScreens {
    @Volatile
    private var service: VelocityScreenService? = null

    /**
     * Creates state and a fresh definition on the UI thread, then replaces this player's previous proxy-owned screen.
     * Construct owner-thread state inside [definition]; capturing state created on another thread is unsupported.
     * Unavailable clients and declarations return a terminal handle with a typed failure.
     */
    @Deprecated("Use VelocityUi.open with a UiDefinition factory.")
    public fun open(
        ownerPlugin: Any,
        player: Player,
        definition: () -> ScreenDefinition,
    ): CompletableFuture<RemoteScreenSession> = active().open(ownerPlugin, player, definition)

    /**
     * Asynchronously reads the successful capability handshake for the current proxy connection.
     */
    public fun capabilities(player: Player): CompletableFuture<RemoteCapabilities?> = active().capabilities(player)

    /**
     * Queues a short state update or read from another Velocity callback onto the UI owner thread.
     * Perform database and network work before this call; [operation] must not block or wait on another UI request.
     */
    public fun <T> execute(
        ownerPlugin: Any,
        operation: () -> T,
    ): CompletableFuture<T> = active().execute(ownerPlugin, operation)

    /**
     * Registers a plugin-owned exact schema for future handshakes; clients install matching factories independently.
     */
    public fun register(
        ownerPlugin: Any,
        type: ProjectionType,
    ): CompletableFuture<Unit> = active().register(ownerPlugin, type)

    /**
     * Withdraws a consumer's handlers and schemas when it stops its service before proxy shutdown.
     * The Strata plugin releases all consumers automatically during proxy shutdown.
     */
    public fun release(ownerPlugin: Any): CompletableFuture<Unit> = active().release(ownerPlugin)

    /**
     * Binds one initialized service or releases it before proxy shutdown.
     */
    internal fun install(service: VelocityScreenService?) {
        check(this.service == null || service == null) { "A Velocity screen service is already installed." }
        this.service = service
    }

    private fun active(): VelocityScreenService = checkNotNull(service) { "The Strata Velocity plugin is not initialized." }
}
