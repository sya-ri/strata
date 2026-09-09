package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.spi.RuntimeUiDiagnosticsOwner
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Minimal scheduling and presentation bridge; state assertions remain shared across every Minecraft version.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal interface ReactiveRenderTestDriver {
    /**
     * Runs one operation synchronously on the actual client owner thread.
     */
    fun <T : Any> onClient(action: () -> T): T

    /**
     * Waits for a predicate sampled on the client thread, with the suite's bounded timeout.
     */
    fun await(condition: () -> Boolean)

    /**
     * Opens a normal production screen and returns its runtime diagnostics capability.
     */
    fun open(definition: ScreenDefinition): RuntimeUiDiagnosticsOwner

    /**
     * Reads existing counters from the current screen on the client thread.
     */
    fun work(): ReactiveNativeWork

    /**
     * Captures the current native image and compares it with an independently rendered literal reference.
     * Called on the suite thread after successful host-frame assertions.
     */
    fun assertPixels(
        definition: ScreenDefinition,
        capture: ReactiveRenderCapture = ReactiveRenderCapture.State,
    )

    /**
     * Closes the screen on its owner thread, including after failed assertions.
     */
    fun closeScreen()
}
