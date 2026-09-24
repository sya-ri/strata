package dev.s7a.strata.velocity.event

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.ui.UiCategory
import dev.s7a.strata.ui.UiPresentation
import dev.s7a.strata.ui.UiSession

/**
 * The client acknowledged the first native presentation. This does not certify a rendered frame.
 * Delivered by Velocity's event manager without blocking the UI worker. Notification only.
 * Queue session access and state changes through VelocityUi.execute; event fields are detached snapshots.
 */
public class StrataUiOpenedEvent(
    public val player: Player,
    public val ownerPlugin: Any,
    public val identity: Long,
    public val session: UiSession,
    public val presentation: UiPresentation,
    public val category: UiCategory?,
)
