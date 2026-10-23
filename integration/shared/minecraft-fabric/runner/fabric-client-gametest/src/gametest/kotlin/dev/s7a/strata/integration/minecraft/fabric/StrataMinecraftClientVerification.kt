@file:Suppress("UnstableApiUsage") // The official Fabric client GameTest entrypoint is an experimental API.

package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/**
 * Runs the target's complete acceptance suite on Fabric's GameTest thread and delegates shutdown to its runner.
 */
public class StrataMinecraftClientVerification : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        FabricMinecraftLoadedTestContext(context).runLoadedSuite()
    }
}
