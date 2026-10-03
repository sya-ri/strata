@file:Suppress("DEPRECATION") // The compiled showcase retains compatibility screen factories alongside current declarations.

package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.ArtifactIdentity
import dev.s7a.strata.performance.LoadedArtifactMetadata
import dev.s7a.strata.performance.MinecraftPerformanceMeter
import dev.s7a.strata.performance.NativePerformanceFixture
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.quality.benchmark.ComponentWorkload
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/**
 * Fixture-only adapter for actual canonical components and the native sampled/custom Canvas scene.
 * The shared meter owns timing, preparation, sample counts, complete frames, counters and diagnostic cleanup.
 * The runner owns one independent process, compiler-selected native operations and post-interval PNG files.
 * This settled presentation corpus does not claim native input, socket scheduling or GPU completion timing.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftNativePerformanceProbe(
    private val context: MinecraftCanvasTestContext,
    private val profile: MinecraftUiProfile,
    private val canvas: MinecraftCanvasTestFixture,
    private val output: Path,
    private val validateViewport: (Int) -> Unit,
) {
    /**
     * Collects every canonical component and the real GPU fixture at four explicitly requested GUI scales.
     * Native resources must be loaded before entry, and output must be fresh for this independent process.
     * Run through production verification so loaded module origins are actual archives rather than development directories.
     * The caller supplies verified host, window and options metadata and restores its viewport after return.
     * [validateViewport] runs on the owner thread and must check actual framebuffer, GUI scale and option values.
     */
    internal fun run(conditions: JsonObject) {
        val report = conditions.deepCopy()
        report.addProperty("schema_version", 1)
        report.addProperty("workload_id", "native-components-presented-v1")
        report.addProperty("run_id", UUID.randomUUID().toString())
        val fixtureType = MinecraftNativePerformanceProbe::class.java
        val fixtureArchive =
            Path.of(
                fixtureType.protectionDomain.codeSource.location
                    .toURI(),
            )
        report.addProperty("fixture_archive", fixtureArchive.toUri().toString())
        report.addProperty("fixture_archive_sha256", ArtifactIdentity.fullCodeSource(MinecraftNativePerformanceProbe::class.java))
        report.addProperty("warmup", PerformancePlan().warmup)
        report.addProperty("settle_frames", 8)
        report.addProperty("preparation_timeout_ms", PerformancePlan().preparationTimeoutMillis)
        report.addProperty(
            "image_directory",
            output
                .resolve("images")
                .toAbsolutePath()
                .normalize()
                .toString(),
        )
        report.add("strata", runtimeMetadata())
        report.addProperty("native_backend", canvas.backend.name)
        report.addProperty("native_driver", canvas.backendDescription)
        val phases = JsonArray()
        report.add("phases", phases)
        for (scale in 1..4) {
            context.configureViewport(viewport, scale)
            ComponentWorkload.entries.forEach { workload ->
                phases.add(measure(scale, workload.name, workload::uiDefinition))
            }
            phases.add(
                measure(scale, "NativeCanvas") {
                    val payload = createNativeCanvasScreenDefinition(canvas).transfer()
                    UiDefinition(payload.title, pausesGame = payload.pausesGame) { payload.content(this) }
                },
            )
            context.waitFor(2400) { canvas.leasesOpened == canvas.leasesClosed && canvas.renderersOpened == canvas.renderersClosed }
        }
        context.waitFor(2400) { canvas.leasesOpened == canvas.leasesClosed && canvas.renderersOpened == canvas.renderersClosed }
        report.add(
            "native_resource_release",
            context.onClient {
                check(0 < canvas.leasesOpened && 0 < canvas.renderersOpened)
                JsonObject().apply {
                    addProperty("leases_opened", canvas.leasesOpened)
                    addProperty("leases_closed", canvas.leasesClosed)
                    addProperty("renderers_opened", canvas.renderersOpened)
                    addProperty("renderers_closed", canvas.renderersClosed)
                }
            },
        )
        check(phases.size() == 4 * (ComponentWorkload.entries.size + 1))
        report.addProperty("status", "passed")
        PerformanceJson.writeNew(output.resolve("report.json"), report)
    }

    private fun runtimeMetadata(): JsonObject =
        context.onClient {
            LoadedArtifactMetadata
                .capture(
                    FabricMinecraftScreen::class.java.classLoader,
                    mapOf(
                        "api" to "dev.s7a.strata.render.DrawImage",
                        "core" to "dev.s7a.strata.runtime.UiSession",
                        "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                        "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
                        "fabric" to FabricMinecraftScreen::class.java.name,
                    ),
                    setOf("api", "core", "minecraft", "fonts"),
                ).also(LoadedArtifactMetadata::verifyComplete)
        }

    @Suppress("TooGenericExceptionCaught") // Native fixture failures remain primary while independent owner-thread cleanup is attempted.
    private fun measure(
        scale: Int,
        name: String,
        definition: () -> UiDefinition,
    ): JsonObject {
        val screen = context.onClient { createMinecraftScreen(definition(), profile, parent = null) }
        var meter: MinecraftPerformanceMeter? = null
        var failure: Throwable? = null
        try {
            context.onClient {
                context.setScreen(screen)
                meter = MinecraftPerformanceMeter(screen)
                checkNotNull(meter).begin(
                    "$name presented scale $scale",
                    PerformancePlan(),
                    NativePerformanceFixture(
                        ready = { context.currentScreen() === screen && context.hasOverlay().not() },
                        settleFrames = 8,
                        validateFrame = {
                            check(context.currentScreen() === screen)
                            validateViewport(scale)
                        },
                    ),
                )
            }
            context.waitFor(2400) { checkNotNull(meter).completed }
            val result = context.onClient { checkNotNull(meter).result() }
            check(result.getAsJsonObject("native_counter_delta").get("renderExtractionCount").asInt == PerformancePlan().samples)
            result.addProperty("case", name)
            result.addProperty("operation", "presented")
            result.addProperty("gui_scale", scale)
            result.addProperty("framebuffer_width", viewport.width)
            result.addProperty("framebuffer_height", viewport.height)
            val screenshot = context.takeScreenshot("performance-$name-scale-$scale", viewport)
            val images = output.resolve("images")
            Files.createDirectories(images)
            Files.copy(screenshot, images.resolve(screenshot.fileName))
            result.addProperty("png_file", screenshot.fileName.toString())
            result.addProperty("png_sha256", ArtifactIdentity.file(screenshot))
            return result
        } catch (caught: Throwable) {
            failure = caught
            try {
                val receipt = context.onClient { meter?.receipt() ?: JsonObject() }
                receipt.addProperty("case", name)
                receipt.addProperty("gui_scale", scale)
                receipt.addProperty("failure", caught.toString())
                PerformanceJson.writeNew(output.resolve("failed-$name-scale-$scale.json"), receipt)
            } catch (storageFailure: Throwable) {
                if (caught !== storageFailure) caught.addSuppressed(storageFailure)
            }
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { meter?.close() ?: Unit } },
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen.close() } },
            )
        }
    }

    private val viewport = IntSize(1920, 1080)
}
