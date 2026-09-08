package dev.s7a.strata.integration.minecraft.fabric

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screens.TitleScreen
import java.lang.Boolean.getBoolean

/**
 * Optional normal-client entrypoint for OS keyboard verification, separate from GameTest's simulated input.
 * It opens one owned editor after the title screen and resources settle; no key or preedit event is injected.
 */
public class ManualImeVerification : ClientModInitializer {
    override fun onInitializeClient() {
        if (getBoolean("strata.ime.manual").not()) return
        check(System.getProperty("fabric.client.gametest") == null) { "OS IME verification requires native input callbacks, which GameTest disables." }
        var session: ManualImeSession? = null
        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            if (session == null && MinecraftClientScreenAccess.currentScreen(minecraft) is TitleScreen && MinecraftClientScreenAccess.hasOverlay(minecraft).not()) {
                session = ManualImeSession(minecraft)
            }
            session?.tick()
        }
    }
}
