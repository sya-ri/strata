package dev.s7a.strata.velocity.event

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.ui.UiCloseReason

/**
 * A previously ready Strata connection has ended; all its UIs are already terminal.
 * Delivered by Velocity's event manager without blocking the UI worker. Notification only.
 * Queue session access and state changes through VelocityUi.execute; event fields are detached snapshots.
 */
public class StrataClientDisconnectedEvent(
    public val player: Player,
    public val reason: UiCloseReason,
)
