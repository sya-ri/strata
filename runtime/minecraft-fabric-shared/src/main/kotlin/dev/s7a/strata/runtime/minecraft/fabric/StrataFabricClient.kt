package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.ScreenPresenter
import dev.s7a.strata.spi.ScreenPresenterRegistration
import dev.s7a.strata.spi.ScreenPresenters
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.Minecraft

/**
 * Fabric client entrypoint that installs the process-wide Strata screen presenter.
 *
 * Fabric owns this entrypoint instance for the client runtime lifetime.
 * Initialization is client-thread confined by Fabric and rejects duplicate invocation without replacing the active presenter.
 */
@Suppress("unused") // Fabric constructs this entrypoint by the class name declared in each version's fabric.mod.json.
@OptIn(InternalStrataRuntimeApi::class)
public class StrataFabricClient : ClientModInitializer {
    private var registration: ScreenPresenterRegistration? = null

    /**
     * Installs the Fabric presenter exactly once for this client runtime.
     *
     * @throws IllegalStateException when this entrypoint is invoked twice or another platform presenter is installed.
     */
    override fun onInitializeClient() {
        check(registration == null) { "The Strata Fabric client runtime is already initialized." }
        registration = ScreenPresenters.install(Presenter)
    }

    private object Presenter : ScreenPresenter {
        override fun present(definition: ScreenDefinition) {
            val minecraft = Minecraft.getInstance()
            FabricScreenPresentationTransaction.present(
                minecraft::isSameThread,
                {
                    val parent = FabricMinecraftScreenAccess.currentScreen(minecraft)
                    val profile =
                        cachedFabricMinecraftProfile(
                            minecraft.resourceManager,
                            fabricMinecraftFontCompatibility(),
                            fabricMinecraftFontOptions(minecraft),
                            ::extractMinecraftUiProfile,
                        )
                    createMinecraftScreen(definition, profile, parent)
                },
                { screen -> FabricMinecraftScreenAccess.setScreen(minecraft, screen) },
            )
        }
    }
}
