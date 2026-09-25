package dev.s7a.strata.velocity.event

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.ui.UiClientCapabilities

/**
 * Successful client negotiation. Emitted once per connection generation.
 * Delivered by Velocity's event manager without blocking the UI worker. Notification only.
 * Queue session access and state changes through VelocityUi.execute; event fields are detached snapshots.
 */
public class StrataClientReadyEvent(
    public val player: Player,
    public val capabilities: UiClientCapabilities,
)
