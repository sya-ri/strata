package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.api.ModInitializer

/**
 * Installs server acceptance through public Fabric and Strata APIs without test packet hooks.
 */
public class FabricServerUiAcceptance : ModInitializer {
    override fun onInitialize() {
        RemoteNativeServerFixture.initialize()
    }
}
