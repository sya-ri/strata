package dev.s7a.strata.runtime.minecraft.fabric

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

/**
 * Prepares native removal events after attachment fails before ordinary screen initialization.
 * The client thread borrows this screen and [client]; initialization failures are suppressed on [primary].
 */
internal fun Screen.prepareFailedAttachment(
    client: Minecraft,
    primary: Throwable,
) {
    runCatching { FabricMinecraftScreenAccess.initialize(client, this) }
        .exceptionOrNull()
        ?.let { FabricMinecraftFailures.addSuppressed(primary, it) }
}
