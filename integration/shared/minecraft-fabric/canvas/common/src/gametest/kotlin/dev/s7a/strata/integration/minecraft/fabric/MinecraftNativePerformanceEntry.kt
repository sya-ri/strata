package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.file.Path

/**
 * Production-only fixture entry point borrowing the existing native coordinator and shared meter.
 * Resources, viewport changes and terminal fences remain outside sampled presentation intervals.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftNativePerformanceEntry {
    /**
     * Collects into a fresh explicit directory and restores all owned resources and pacing options.
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
        output: Path,
    ) {
        val options = context.onClient { MinecraftNativePerformanceOptions() }
        var fixture: MinecraftCanvasTestFixture? = null
        var failure: Throwable? = null
        try {
            val native = context.onClient { MinecraftCanvasTestFixture(createMinecraftCanvasTestResources()) }
            fixture = native
            context.configureViewport(IntSize(1920, 1080), 1)
            val conditions = context.onClient { options.conditions() }
            MinecraftNativePerformanceProbe(context, profile, native, output, options::validate).run(conditions)
        } catch (caught: Throwable) {
            failure = caught
            try {
                PerformanceJson.writeNew(output.resolve("failed-entry.json"), JsonObject().apply { addProperty("failure", caught.toString()) })
            } catch (storageFailure: Throwable) {
                if (caught !== storageFailure) caught.addSuppressed(storageFailure)
            }
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.waitFor(2400) { fixture?.let { it.leasesOpened == it.leasesClosed && it.renderersOpened == it.renderersClosed } ?: true } },
                { context.onClient { fixture?.close() ?: Unit } },
                { context.onClient { options.close() } },
            )
        }
    }
}
