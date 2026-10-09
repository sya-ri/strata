package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiClientCapabilities
import dev.s7a.strata.ui.UiCloseReason
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession
import net.fabricmc.loader.api.ModContainer
import net.minecraft.server.level.ServerPlayer

/**
 * Committed lifecycle notifications emitted inside the service's execution owner.
 * Notifications run on the server thread; listener failures never undo committed state.
 * UI identity is scoped to the service and connection.
 * Capability and presentation values are snapshots; player, owner Mod, and session references retain their native ownership.
 */
public sealed interface StrataUiEvent {
    /**
     * Authenticated native player belonging to this notification.
     */
    public val player: ServerPlayer

    /**
     * Successful negotiation, once per connection generation.
     */
    public data class Ready(
        override val player: ServerPlayer,
        public val capabilities: UiClientCapabilities,
    ) : StrataUiEvent

    /**
     * Retirement of a previously ready connection after every UI has closed.
     */
    public data class Disconnected(
        override val player: ServerPlayer,
        public val reason: UiCloseReason,
    ) : StrataUiEvent

    /**
     * First acknowledged native presentation, including initially hidden HUDs.
     */
    public data class Opened(
        override val player: ServerPlayer,
        public val owner: ModContainer,
        public val identity: Long,
        public val session: UiSession,
        public val presentation: UiPresentation,
        public val category: UiCategory?,
    ) : StrataUiEvent

    /**
     * Acknowledged transition between distinct presentations of one retained session.
     */
    public data class PresentationChanged(
        override val player: ServerPlayer,
        public val owner: ModContainer,
        public val identity: Long,
        public val session: UiSession,
        public val previous: UiPresentation,
        public val presentation: UiPresentation,
        public val category: UiCategory?,
    ) : StrataUiEvent

    /**
     * Terminal ownership, including rejected openings that never emitted [Opened].
     */
    public data class Closed(
        override val player: ServerPlayer,
        public val owner: ModContainer,
        public val identity: Long,
        public val session: UiSession,
        public val presentation: UiPresentation?,
        public val category: UiCategory?,
        public val reason: UiCloseReason,
    ) : StrataUiEvent
}
