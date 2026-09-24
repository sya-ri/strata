package dev.s7a.strata.velocity

import com.velocitypowered.api.proxy.Player
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import java.util.concurrent.CompletableFuture

/**
 * Queues an available definition through the installed Strata Velocity runtime.
 * Create state inside [VelocityUi.open] or [VelocityUi.execute]; constructing mutable state on an event thread is unsupported.
 */
public fun UiDefinition.open(
    ownerPlugin: Any,
    player: Player,
): CompletableFuture<UiSession> = VelocityUi.open(ownerPlugin, player) { this }
