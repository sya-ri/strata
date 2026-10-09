package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Text
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.runtime.minecraft.fabric.Strata
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import dev.s7a.strata.ui.UiSession
import net.fabricmc.loader.api.ModContainer
import net.minecraft.server.level.ServerPlayer

/**
 * Compiled public-API example for an application-owned Fabric server counter.
 */
public object FabricServerUiExample {
    /**
     * Opens a counter whose state and button callback stay on the server thread.
     */
    public fun open(
        owner: ModContainer,
        player: ServerPlayer,
    ): UiSession =
        Strata.open(owner, player) {
            val count = mutableStateOf(0)
            UiDefinition("Server counter") {
                Column(spacing = 4) {
                    Text("Count: ${count.value}")
                    Button("Increment", 100, modifier = Modifier.Empty.onActivate { count.value++ })
                }
            }
        }
}
