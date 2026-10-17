package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen

/**
 * Orders screenshots after completed native presentation events observed only by the test fixture.
 * Client ticks alone do not establish presentation order.
 * The framework callback is serialized on the client thread; subsequent screenshot work runs after it.
 * Production runtimes expose no frame counter or measurement hook.
 */
internal object MinecraftCanvasFrameFence {
    /**
     * Registers observation on first use and reads completed framework frames on the client thread.
     * The returned count starts at zero when this fixture first observes the screen.
     */
    internal fun completedFrames(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
    ): Long = context.onClient { MinecraftPresentationObservation.completedFrames(screen) }

    /**
     * Waits for a completed framework frame after the baseline, failing through the context's bounded wait.
     * The baseline must come from [completedFrames] for the same screen before the work being fenced.
     */
    internal fun awaitCompletedFrame(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        baseline: Long,
    ) {
        context.waitFor { baseline < MinecraftPresentationObservation.completedFrames(screen) }
    }
}
