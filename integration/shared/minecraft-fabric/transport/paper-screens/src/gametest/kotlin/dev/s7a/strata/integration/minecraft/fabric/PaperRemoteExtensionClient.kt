package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.examples.paper.DemoRemoteExtensions
import dev.s7a.strata.runtime.minecraft.fabric.FabricRemoteScreens
import net.fabricmc.api.ClientModInitializer

/**
 * Installs the separately compiled example extension before the first play connection freezes client capabilities.
 */
public class PaperRemoteExtensionClient : ClientModInitializer {
    override fun onInitializeClient() {
        DemoRemoteExtensions.registerClient(FabricRemoteScreens.registry)
    }
}
