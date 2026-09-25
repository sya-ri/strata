package dev.s7a.strata.velocity

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import java.util.concurrent.CompletableFuture

/**
 * Application API supplied by the installed Strata Velocity plugin.
 * Construct owner-thread state inside [open], and queue external state or session access through [execute].
 * Never block the UI thread on returned futures. Plugin events contain snapshots and do not block UI processing.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object VelocityUi {
    @Volatile
    private var provider: VelocityUiProvider? = null

    /**
     * Creates state and a fresh one-shot definition on the UI thread, then opens the owned session.
     */
    public fun open(
        ownerPlugin: Any,
        player: Player,
        definition: () -> UiDefinition,
    ): CompletableFuture<UiSession> = active().open(ownerPlugin, player, definition)

    /**
     * Asynchronously reads the current negotiated support, or null while unavailable.
     */
    public fun capabilities(player: Player): CompletableFuture<UiClientCapabilities?> = active().capabilities(player)

    /**
     * Queues short state updates or session operations on the owner thread.
     * Complete blocking work before calling this method.
     */
    public fun <T> execute(
        ownerPlugin: Any,
        operation: () -> T,
    ): CompletableFuture<T> = active().execute(ownerPlugin, operation)

    /**
     * Registers a plugin-owned exact schema for future client negotiations.
     */
    public fun register(
        ownerPlugin: Any,
        type: ProjectionType,
    ): CompletableFuture<Unit> = active().register(ownerPlugin, type)

    /**
     * Releases one consumer's sessions and schemas before proxy shutdown.
     */
    public fun release(ownerPlugin: Any): CompletableFuture<Unit> = active().release(ownerPlugin)

    /**
     * Installs one runtime; closing the registration releases only that installation.
     */
    @InternalStrataRuntimeApi
    public fun install(provider: VelocityUiProvider): AutoCloseable =
        synchronized(this) {
            check(this.provider == null) { "A Velocity UI runtime is already installed." }
            this.provider = provider
            var closed = false
            AutoCloseable {
                synchronized(this) {
                    if (closed.not()) {
                        closed = true
                        if (this.provider === provider) this.provider = null
                    }
                }
            }
        }

    private fun active(): VelocityUiProvider = checkNotNull(provider) { "The Strata Velocity plugin is not initialized." }
}
