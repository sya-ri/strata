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
import dev.s7a.strata.performance.PerformanceProfile
import dev.s7a.strata.performance.PerformanceSelection
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
 * Optional native queries report GUI GPU work separately from CPU extraction and observed frame intervals.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftNativePerformanceProbe(
    private val context: MinecraftCanvasTestContext,
    private val profile: MinecraftUiProfile,
    private val canvas: MinecraftCanvasTestFixture,
    private val output: Path,
    private val validateViewport: (Int) -> Unit,
) {
    private val profileMode = PerformanceProfile.fromQuickFlag(System.getProperty("strata.performance.quick"))
    private val plan = profileMode.plan()
    private val sampledCorpus = System.getProperty("strata.performance.sampledImages", "false").toBooleanStrict()
    private val independentCorpus =
        System.getProperty("strata.performance.nativeFixture")?.let { name ->
            val type = Class.forName(name, false, javaClass.classLoader).asSubclass(MinecraftNativePerformanceCorpus::class.java)
            check(ArtifactIdentity.fullCodeSource(type) == ArtifactIdentity.fullCodeSource(javaClass)) { "An independent native fixture must belong to the verified fixture archive." }
            val constructor = type.getDeclaredConstructor()
            check(constructor.trySetAccessible())
            constructor.newInstance()
        }
    private val gpuQueries = System.getProperty("strata.performance.gpuQueries", "false").toBooleanStrict()

    /**
     * Collects the selected component matrix and GUI scales using the declared measurement profile.
     * Native resources must be loaded before entry, and output must be fresh for this independent process.
     * Run through production verification so loaded module origins are actual archives rather than development directories.
     * The caller supplies verified host, window and options metadata and restores its viewport after return.
     * [validateViewport] runs on the owner thread and must check actual framebuffer, GUI scale and option values.
     */
    internal fun run(conditions: JsonObject) {
        val selection = selection()
        val report = createReport(conditions, selection)
        val phases = JsonArray()
        report.add("phases", phases)
        val scales = profileMode.viewports((1..4).toList())
        for (scale in scales) {
            context.configureViewport(viewport, scale)
            ComponentWorkload.entries.filter { it.name in selection.ids }.forEach { workload ->
                phases.add(measure(scale, workload.name, definition = workload::uiDefinition))
            }
            if ("NativeCanvas" in selection.ids) {
                phases.add(measureNativeCanvas(scale))
            }
            MinecraftSampledPerformanceCase.entries.filter { it.name in selection.ids }.forEach { case ->
                val scene = context.onClient { MinecraftSampledPerformanceScene(case, IntSize(viewport.width / scale, viewport.height / scale), plan.warmup + plan.samples) }
                phases.add(measure(scale, case.name, update = { scene.update() }, definition = scene::definition))
            }
            independentCorpus?.let { corpus ->
                corpus.caseIds.filter { it in selection.ids }.forEach { id ->
                    val scene = context.onClient { corpus.scene(id, IntSize(viewport.width / scale, viewport.height / scale), plan.warmup + plan.samples) }
                    phases.add(measure(scale, id, update = { scene.update() }, definition = scene::definition))
                }
            }
            context.waitFor(2400) { canvas.leasesOpened == canvas.leasesClosed && canvas.renderersOpened == canvas.renderersClosed }
        }
        context.waitFor(2400) { canvas.leasesOpened == canvas.leasesClosed && canvas.renderersOpened == canvas.renderersClosed }
        report.add(
            "native_resource_release",
            context.onClient {
                if ("NativeCanvas" in selection.ids) check(0 < canvas.leasesOpened && 0 < canvas.renderersOpened)
                JsonObject().apply {
                    addProperty("leases_opened", canvas.leasesOpened)
                    addProperty("leases_closed", canvas.leasesClosed)
                    addProperty("renderers_opened", canvas.renderersOpened)
                    addProperty("renderers_closed", canvas.renderersClosed)
                }
            },
        )
        check(phases.size() == scales.size * selection.ids.size)
        report.addProperty("status", "passed")
        PerformanceJson.writeNew(output.resolve("report.json"), report)
    }

    private fun selection(): PerformanceSelection {
        check(independentCorpus == null || sampledCorpus.not()) { "Independent and ordinary sampled-image corpora are separate scopes." }
        val cases = independentCorpus?.caseIds ?: if (sampledCorpus) MinecraftSampledPerformanceCase.entries.map { it.name }.toSet() else ComponentWorkload.entries.map { it.name }.toSet() + "NativeCanvas"
        return PerformanceSelection(cases, System.getProperty("strata.performance.workloads") ?: if (profileMode == PerformanceProfile.Quick) cases.first() else null)
    }

    private fun createReport(
        conditions: JsonObject,
        selection: PerformanceSelection,
    ): JsonObject {
        val family = independentCorpus?.family ?: if (sampledCorpus) "native-sampled-images" else "native-components"
        val report = conditions.deepCopy()
        report.add("selected_cases", JsonArray().apply { selection.ids.forEach(::add) })
        independentCorpus?.let { corpus ->
            report.addProperty("independent_fixture_family", corpus.family)
            report.addProperty("independent_fixture_class", corpus.javaClass.name)
            report.add("independent_fixture_cases", JsonArray().apply { corpus.caseIds.forEach(::add) })
        }
        report.addProperty("schema_version", 1)
        report.addProperty("workload_id", profileMode.workloadId(if (selection.narrowed) "$family-selected-presented-v1" else "$family-presented-v1"))
        if (profileMode == PerformanceProfile.Quick) report.addProperty("measurement_profile", profileMode.name)
        report.addProperty("run_id", UUID.randomUUID().toString())
        val fixtureType = MinecraftNativePerformanceProbe::class.java
        val fixtureArchive =
            Path.of(
                fixtureType.protectionDomain.codeSource.location
                    .toURI(),
            )
        report.addProperty("fixture_archive", fixtureArchive.toUri().toString())
        report.addProperty("fixture_archive_sha256", ArtifactIdentity.fullCodeSource(MinecraftNativePerformanceProbe::class.java))
        report.addProperty("warmup", plan.warmup)
        report.addProperty("settle_frames", 8)
        report.addProperty("preparation_timeout_ms", plan.preparationTimeoutMillis)
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
        report.addProperty("native_gpu_requested", gpuQueries)
        return report
    }

    /**
     * Measures the sampled/custom Canvas scene using the same presented-frame boundary as components.
     */
    private fun measureNativeCanvas(scale: Int): JsonObject =
        measure(scale, "NativeCanvas") {
            val payload = createNativeCanvasScreenDefinition(canvas).transfer()
            UiDefinition(payload.title, pausesGame = payload.pausesGame) { payload.content(this) }
        }

    private fun runtimeMetadata(): JsonObject =
        context.onClient {
            LoadedArtifactMetadata
                .capture(
                    FabricMinecraftScreen::class.java.classLoader,
                    mapOf(
                        "api" to "dev.s7a.strata.render.DrawImage",
                        "core" to "dev.s7a.strata.runtime.UiSession",
                        "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                        "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                        "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
                        "fabric" to FabricMinecraftScreen::class.java.name,
                    ),
                    setOf("api", "core", "headless", "minecraft", "fonts"),
                ).also(LoadedArtifactMetadata::verifyComplete)
        }

    @Suppress("TooGenericExceptionCaught") // Native fixture failures remain primary while independent owner-thread cleanup is attempted.
    private fun measure(
        scale: Int,
        name: String,
        update: (Int) -> Unit = {},
        definition: () -> UiDefinition,
    ): JsonObject {
        val screen = context.onClient { createMinecraftScreen(definition(), profile, parent = null) }
        var gpu: MinecraftNativeGpuProbe? = null
        var collecting = false
        var meter: MinecraftPerformanceMeter? = null
        var failure: Throwable? = null
        try {
            context.onClient {
                gpu = createGpuProbe(plan.samples)
                context.setScreen(screen)
                meter = MinecraftPerformanceMeter(screen)
                checkNotNull(meter).begin(
                    "$name presented scale $scale",
                    plan,
                    NativePerformanceFixture(
                        ready = { context.currentScreen() === screen && context.hasOverlay().not() },
                        settleFrames = 8,
                        validateFrame = {
                            check(context.currentScreen() === screen)
                            validateViewport(scale)
                        },
                        beforeSamples = { collecting = true },
                    ),
                    update = { index ->
                        if (collecting) gpu?.arm()
                        update(index)
                    },
                )
            }
            context.waitFor(2400) { checkNotNull(meter).completed }
            return collectResult(scale, name, checkNotNull(meter), gpu)
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
                { context.onClient { gpu?.close() ?: Unit } },
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen.close() } },
            )
        }
    }

    private fun collectResult(
        scale: Int,
        name: String,
        meter: MinecraftPerformanceMeter,
        gpu: MinecraftNativeGpuProbe?,
    ): JsonObject {
        val result = context.onClient { meter.result() }
        val queries = gpu
        if (queries != null) {
            context.waitFor(2400) { queries.completed }
            context.onClient { queries.append(result) }
        }
        check(result.getAsJsonObject("native_counter_delta").get("renderExtractionCount").asInt == plan.samples)
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
    }

    // The optional class is compiled only for the native timestamp-query family; other targets report unavailable.
    private fun createGpuProbe(samples: Int): MinecraftNativeGpuProbe? {
        if (gpuQueries.not()) return null
        val adapter =
            try {
                Class.forName("dev.s7a.strata.integration.minecraft.fabric.MinecraftGpuPerformanceProbe")
            } catch (_: ClassNotFoundException) {
                return null
            }
        return adapter.getConstructor(Int::class.javaPrimitiveType).newInstance(samples) as MinecraftNativeGpuProbe
    }

    private val viewport = IntSize(1920, 1080)
}
