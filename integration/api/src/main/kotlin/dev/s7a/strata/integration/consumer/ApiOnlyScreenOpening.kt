package dev.s7a.strata.integration.consumer

import dev.s7a.strata.screen.ScreenOpenThreadException
import dev.s7a.strata.screen.ScreenRuntimeUnavailableException

/**
 * Creates and opens the representative consumer screen while compiling against the API artifact alone.
 *
 * The installed platform runtime is discovered behind the API and owns the definition after successful transfer.
 *
 * @param onClose action invoked by the screen close button.
 * @throws ScreenRuntimeUnavailableException when no runtime is installed.
 * @throws ScreenOpenThreadException when called away from the platform owner thread.
 */
public fun openApiOnlyScreen(onClose: () -> Unit) {
    createApiOnlyScreenDefinition(onClose).open()
}
