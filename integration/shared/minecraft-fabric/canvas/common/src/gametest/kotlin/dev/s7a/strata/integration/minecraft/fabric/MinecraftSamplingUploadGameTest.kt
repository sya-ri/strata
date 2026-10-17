package dev.s7a.strata.integration.minecraft.fabric

import com.google.gson.JsonObject
import dev.s7a.strata.component.PanZoomState
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.TiledImage
import dev.s7a.strata.component.TiledImageLevel
import dev.s7a.strata.component.TiledImageSource
import dev.s7a.strata.component.TiledImageTile
import dev.s7a.strata.component.TiledImageTileId
import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.geometry.LongRect
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.performance.MinecraftPerformanceMeter
import dev.s7a.strata.performance.NativePerformanceFixture
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePlan
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.canvas.NativeCanvasDevices
import dev.s7a.strata.runtime.minecraft.fabric.FabricMinecraftScreen
import dev.s7a.strata.runtime.minecraft.fabric.createMinecraftScreen
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.StateRevision
import dev.s7a.strata.state.StateSnapshot
import dev.s7a.strata.state.StateSource
import dev.s7a.strata.state.StateSubscription
import dev.s7a.strata.ui.UiDefinition

/**
 * Records warmed standard map/head uploads and verifies current-geometry reuse through real native presentation.
 * Four generated tiles and sixty-four resolved heads use immutable pixels and no network or time-dependent content.
 * The repeating transform covers one full 120-frame cycle before 180 samples; idle uses 30 warm-up frames.
 * These are deterministic work controls, not controlled GPU timing or an FPS comparison.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object MinecraftSamplingUploadGameTest {
    /**
     * Runs idle, repeating motion and held-geometry intervals at GUI scale one, then checks physical retirement.
     */
    @Suppress("TooGenericExceptionCaught") // Every failure remains primary while independent native owners close.
    internal fun run(
        context: MinecraftCanvasTestContext,
        profile: MinecraftUiProfile,
    ) {
        context.configureViewport(IntSize(1920, 1080), 1)
        val scene = context.onClient { Scene() }
        val screen = context.onClient { createMinecraftScreen(scene.definition(), profile, parent = null) }
        var failure: Throwable? = null
        try {
            context.onClient { context.setScreen(screen) }
            measure(context, screen, scene, Phase.Idle)
            measure(context, screen, scene, Phase.Motion)
            measure(context, screen, scene, Phase.Held)
            context.takeScreenshot("strata-sampling-upload-held", IntSize(1920, 1080))
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(
                failure,
                { context.onClient { context.setScreen(null) } },
                { context.onClient { screen.close() } },
                { context.waitFor { NativeCanvasDevices.retainedTargetCount() == 0 && NativeCanvasDevices.retainedGuiResourceSetCount() == 0 } },
            )
        }
    }

    @Suppress("TooGenericExceptionCaught") // The meter's client-thread cleanup must preserve the original callback or assertion failure.
    private fun measure(
        context: MinecraftCanvasTestContext,
        screen: FabricMinecraftScreen,
        scene: Scene,
        phase: Phase,
    ) {
        val meter = context.onClient { MinecraftPerformanceMeter(screen) }
        var failure: Throwable? = null
        try {
            var requests = 0L
            var evaluations = 0L
            var index = 0
            context.onClient {
                meter.begin(
                    "sampling-upload-${phase.name}",
                    PerformancePlan(warmup = phase.warmup, samples = phase.samples, repetitions = 1),
                    NativePerformanceFixture(
                        beforeSamples = {
                            requests = scene.requests
                            evaluations = scene.evaluations
                        },
                    ),
                    update = {
                        if (phase == Phase.Motion) scene.move(index)
                        index += 1
                    },
                )
            }
            context.waitFor(2400) { meter.completed }
            val result = context.onClient { meter.result() }
            verify(result, phase)
            check(context.onClient { scene.requests } == requests) { "Warm presentation requested tiles again." }
            check(context.onClient { scene.evaluations } == evaluations) { "Navigation reevaluated application content." }
            result.addProperty("tile_requests_during_samples", 0)
            result.addProperty("application_evaluations_during_samples", 0)
            result.addProperty("gui_scale", 1)
            result.addProperty("motion_period_frames", 120)
            result.addProperty("warmup", phase.warmup)
            result.addProperty("purpose", "Upload accounting and current-geometry reuse; timings are not a formal performance comparison")
            PerformanceJson.writeNew(context.outputDirectory.resolve("sampling-upload-${phase.name}.json"), result)
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            runCanvasTestCleanup(failure, { context.onClient { meter.close() } })
        }
    }

    private fun verify(
        result: JsonObject,
        phase: Phase,
    ) {
        val delta = result.getAsJsonObject("native_counter_delta")
        val payload = result.getAsJsonObject("native_payload")
        val raster = payload.get("rasterUploadCount").asLong
        val sampling = payload.get("samplingUploadCount").asLong
        check(raster + sampling == delta.get("textureUploadCount").asLong) { "Portable uploads lost their raster/metadata partition: $result" }
        check(raster == delta.get("portableRasterizationCount").asLong) { "CPU raster uploads were attributed to metadata: $result" }
        check(payload.get("sourceUploadByteCount").asLong == 0L) { "Warmed presentation uploaded immutable source pixels: $result" }
        listOf("sampledImageUploadCount", "sampledImageDirectMissCount", "sampledImageEvictionCount", "sampledImageCapacityFallbackCount").forEach { name ->
            check(delta.get(name).asLong == 0L) { "Warmed presentation repeated source work in $name: $result" }
        }
        if (phase != Phase.Motion) {
            check(raster == 0L && sampling == 0L) { "Unchanged geometry repeated a portable upload: $result" }
            check(payload.get("rasterUploadByteCount").asLong == 0L && payload.get("samplingUploadByteCount").asLong == 0L) { "Reused geometry transferred portable payload bytes: $result" }
        } else {
            check(0L < raster + sampling) { "The motion control did not change portable output: $result" }
        }
    }

    private enum class Phase(
        val warmup: Int,
        val samples: Int,
    ) {
        Idle(30, 180),
        Motion(120, 180),
        Held(0, 30),
    }

    /**
     * Client-owned immutable inputs and one navigation state; no resource or historical geometry is retained.
     */
    private class Scene {
        var requests = 0L
            private set
        var evaluations = 0L
            private set
        private val navigation = PanZoomState()
        private val tiles =
            List(4) { tile ->
                val pixels = createDrawImage(IntSize(128, 128)) { x, y -> 0xFF000000.toInt() or ((tile * 1337 + x * 73471 + y * 7919) and 0xFFFFFF) }
                StateSource<TiledImageTile> { StateSubscription(StateSnapshot(StateRevision(0L), TiledImageTile.Ready(pixels))) {} }
            }
        private val skins =
            List(8) { skin ->
                PlayerSkinSource.Pixels(createDrawImage(IntSize(64, 64)) { x, y -> 0xFF000000.toInt() or ((skin * 7919 + x * 1337 + y * 73471) and 0xFFFFFF) })
            }
        private val source =
            object : TiledImageSource {
                override val bounds: LongRect = LongRect(0, 0, 256, 256)
                override val levels: List<TiledImageLevel> = listOf(TiledImageLevel(IntSize(128, 128), contentUnitsPerPixel = 1L))

                override fun tile(id: TiledImageTileId): StateSource<TiledImageTile> {
                    requests += 1
                    check(id.level == 0 && id.column in 0L..1L && id.row in 0L..1L)
                    return tiles[(id.row * 2 + id.column).toInt()]
                }
            }

        /**
         * Changes only retained navigation outside declarative evaluation; the trajectory repeats exactly every 120 updates.
         */
        fun move(index: Int) {
            val step = index % 120
            val distance = minOf(step, 120 - step).toDouble()
            navigation.zoomTo(1.1 + distance / 600.0)
            navigation.centerOn(DoubleOffset(128.0 + distance / 15.0, 128.0 - distance / 20.0))
        }

        /**
         * Builds ordinary public components with resolved skins, stable image identities and fixed overlay coordinates.
         */
        fun definition(): UiDefinition =
            UiDefinition("Sampling metadata uploads") {
                evaluations += 1
                Stack(Modifier.Empty.fillMaxSize().background(ArgbColor(0xFF000000.toInt()))) {
                    TiledImage(source, navigation, IntSize(320, 320)) {
                        repeat(64) { index ->
                            PlayerHead(
                                source = skins[index % skins.size],
                                scale = PlayerHeadScale(2),
                                modifier = Modifier.Empty.atContentPosition(DoubleOffset(16.0 + index % 8 * 32, 16.0 + index / 8 * 32)),
                            )
                        }
                    }
                }
            }
    }
}
