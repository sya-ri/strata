@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.screen.ScreenOpenThreadException
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.spi.ScreenPresenter
import dev.s7a.strata.spi.ScreenPresenterRegistration
import dev.s7a.strata.spi.ScreenPresenters
import dev.s7a.strata.spi.UiPresenters
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
    private var uiRegistration: AutoCloseable? = null
    private var registration: ScreenPresenterRegistration? = null

    /**
     * Installs the Fabric presenter exactly once for this client runtime.
     *
     * @throws IllegalStateException when this entrypoint is invoked twice or another platform presenter is installed.
     */
    override fun onInitializeClient() {
        check(registration == null) { "The Strata Fabric client runtime is already initialized." }
        uiRegistration = UiPresenters.install { FabricUiSessions.open(it) }
        registration = ScreenPresenters.install(Presenter)
    }

    private object Presenter : ScreenPresenter {
        override fun present(definition: ScreenDefinition) {
            val minecraft = Minecraft.getInstance()
            if (minecraft.isSameThread().not()) throw ScreenOpenThreadException("UI opening requires the Minecraft client thread.")
            definition.asUiDefinition().open()
        }
    }
}
