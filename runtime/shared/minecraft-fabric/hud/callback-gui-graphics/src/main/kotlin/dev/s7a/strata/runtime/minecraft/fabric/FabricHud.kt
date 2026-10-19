@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.spi.InternalStrataRuntimeApi
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback

/**
 * Registers Strata overlays through the selected official Fabric HUD API.
 */
public object FabricHud {
    /**
     * Registers one process-lifetime HUD element with Fabric's native layer and visibility contract.
     */
    @JvmStatic
    public fun initialize() {
        HudRenderCallback.EVENT.register { graphics, _ ->
            FabricUiSessions.renderHuds { screen, mouseX, mouseY ->
                screen.render(graphics, mouseX, mouseY, 0.0f)
            }
        }
    }
}
