package dev.s7a.strata.velocity.event

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession

/**
 * The client acknowledged a different presentation for the same retained UI.
 * Delivered by Velocity's event manager without blocking the UI worker. Notification only.
 * Queue session access and state changes through VelocityUi.execute; event fields are detached snapshots.
 */
public class StrataUiPresentationChangedEvent(
    public val player: Player,
    public val ownerPlugin: Any,
    public val identity: Long,
    public val session: UiSession,
    public val previous: UiPresentation,
    public val presentation: UiPresentation,
    public val category: UiCategory?,
)
