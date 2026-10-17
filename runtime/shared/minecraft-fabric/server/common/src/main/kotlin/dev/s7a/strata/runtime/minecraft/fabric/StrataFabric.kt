package dev.s7a.strata.runtime.minecraft.fabric

import net.fabricmc.api.ModInitializer

/**
 * Registers networking and logical-server lifecycle on either physical environment.
 */
public class StrataFabric : ModInitializer {
    override fun onInitialize() {
        FabricRemoteServerTransport.initialize()
        FabricServerUiServices.initialize()
    }
}
