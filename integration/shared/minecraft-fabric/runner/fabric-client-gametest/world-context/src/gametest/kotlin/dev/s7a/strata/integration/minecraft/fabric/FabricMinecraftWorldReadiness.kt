@file:Suppress("UnstableApiUsage") // Calls the official Fabric client GameTest world readiness helper.

package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext

/**
 * Waits for Fabric's client world rendering barrier through this API family's accessor.
 */
internal fun TestSingleplayerContext.awaitFabricChunks() {
    clientWorld.waitForChunksRender()
}
