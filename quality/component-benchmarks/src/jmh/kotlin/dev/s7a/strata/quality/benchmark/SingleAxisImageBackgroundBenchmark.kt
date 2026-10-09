package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.MinecraftTileBackgroundReference
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.fillMaxSize
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.MutableState
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Separates actual background collection/admission, declaration and complete retained-frame boundaries.
 * Every method uses the same typed corpus, immutable sources and actual supplied runtime archives.
 * JMH owns all timing/allocation sampling; deterministic work and independent pixel checks run before forks.
 * These JVM operations measure no native uploads, draws, offscreen composition or GPU/FPS result.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class SingleAxisImageBackgroundBenchmark {
    /**
     * Collects a genuine node callback without subsequent dense admission or transformation.
     */
    @Benchmark
    public fun paintCollector(state: TileSession): List<*> = state.paintCollector()

    /**
     * Includes actual dense-list admission after the same callback; integer templates remain lazy.
     */
    @Benchmark
    public fun paintAdmission(state: TileSession): List<*> = state.paintAdmission()

    /**
     * Creates, attaches and closes a real definition/host without requesting its first paint frame.
     */
    @Benchmark
    public fun declaration(state: TileSession): MinecraftUiHost = state.declaration()

    /**
     * Replaces immutable pixels and returns the complete resulting retained frame.
     */
    @Benchmark
    public fun dirtySource(state: TileSession): RuntimeUiFrame = state.dirtySource()

    /**
     * Alternates actual viewport constraints through the retained host, including complete required paint.
     */
    @Benchmark
    public fun geometry(state: TileSession): RuntimeUiFrame = state.geometry()

    /**
     * Returns the complete unchanged frame; this control regenerates no local tiles.
     */
    @Benchmark
    public fun clean(state: TileSession): RuntimeUiFrame = state.clean()

    /**
     * One owner-worker host and two externally prepared immutable sources; no history or timing loop is retained.
     */
    @State(Scope.Thread)
    @Suppress("TooManyFunctions") // One JMH state owns the six independently measured boundaries and their untimed acceptance checks.
    public open class TileSession {
        /**
         * Whole singleton-axis/control selection, validated from compiled JMH metadata.
         */
        @JvmField
        @Param
        public var workload: SingleAxisImageTileWorkload = SingleAxisImageTileWorkload.SinglePixelLarge

        private lateinit var profile: MinecraftUiProfile
        private lateinit var images: List<DrawImage>
        private lateinit var source: MutableState<Int>
        private lateinit var host: MinecraftUiHost
        private lateinit var producer: SingleAxisImageTileProducer
        private var monitor: RuntimeWorkMonitor? = null
        private var size = IntSize.Zero
        private var geometryRevision = 0

        /**
         * Creates fixed assets, real collector adapters and an initial retained frame outside measurement.
         */
        @Setup(Level.Trial)
        public fun setup() {
            profile = ComponentProfile.create()
            images = List(2) { phase -> image(workload.source, phase) }
            source = mutableStateOf(0)
            size = workload.viewport
            producer = SingleAxisImageTileProducer(workload, images.first())
            host = createHost()
            host.attach()
            host.frame(size)
        }

        /**
         * Real collector-only producer boundary; scope construction and immutable snapshot are included.
         */
        public fun paintCollector(): List<*> = producer.collect()

        /**
         * Real callback plus the unchanged dense-list admission implementation.
         */
        public fun paintAdmission(): List<*> = producer.collectAndAdmit()

        /**
         * Evaluates/reconciles a fresh real declaration and releases its host without painting.
         */
        public fun declaration(): MinecraftUiHost =
            createHost().use { fresh ->
                fresh.attach()
                fresh
            }

        /**
         * Publishes one unequal immutable image and extracts the complete changed frame.
         */
        public fun dirtySource(): RuntimeUiFrame {
            source.value = 1 - source.value
            return measured { host.frame(size) }
        }

        /**
         * Alternates unchanged and one-pixel-larger viewport dimensions, preserving the source identity.
         */
        public fun geometry(): RuntimeUiFrame {
            geometryRevision += 1
            val offset = geometryRevision % 2
            size = IntSize(workload.viewport.width + offset, workload.viewport.height + offset)
            return measured { host.frame(size) }
        }

        /**
         * Complete same-host/same-geometry reuse control.
         */
        public fun clean(): RuntimeUiFrame = measured { host.frame(size) }

        /**
         * Bounded shared monitoring for admission only; never enabled by JMH setup or measured methods.
         */
        public fun monitorWork() {
            check(monitor == null)
            monitor = RuntimeWorkMonitor(host, checkpointSamples = 64)
        }

        /**
         * Detaches actual interval metrics; the monitor does not invent producer or native counters.
         */
        public fun diagnostics(): JsonObject = checkNotNull(monitor).snapshot()

        /**
         * Observes actual producer collection/original/span/template membership outside timing.
         */
        public fun producerWork(): JsonObject = producer.work()

        /**
         * Checks complete pixels against the independent original tiler/ordered ARGB oracle at two physical densities.
         * Fractional presentation additionally preserves every original sampled source/destination and shared edge.
         */
        public fun verifyPixels(frame: RuntimeUiFrame) {
            val sourceImage = images[source.value]
            val expected = originalCommands(sourceImage, frame.size)
            if (workload.mapping == SingleAxisImageTileWorkload.Mapping.Fractional) {
                check(frame.drawCommands.filterIsInstance<DrawCommand.SampledImage>() == expected.filterIsInstance<DrawCommand.SampledImage>())
            }
            for (density in listOf(1, 2)) {
                check(pixels(frame, density).contentEquals(MinecraftTileBackgroundReference.pixels(expected, frame.size, density))) {
                    "Complete singleton-axis background pixels changed: $workload density=$density size=${frame.size}"
                }
            }
        }

        private fun originalCommands(
            image: DrawImage,
            viewport: IntSize,
        ): List<DrawCommand> {
            val commands =
                when (workload.mapping) {
                    SingleAxisImageTileWorkload.Mapping.Tile -> MinecraftTileBackgroundReference.scalar(image, viewport)
                    SingleAxisImageTileWorkload.Mapping.Fractional -> MinecraftTileBackgroundReference.fitted(image, workload.design, viewport)
                    SingleAxisImageTileWorkload.Mapping.Stretch -> if (viewport.width == 0 || viewport.height == 0) emptyList() else listOf(DrawCommand.BlitImage(image, IntRect(0, 0, image.size.width, image.size.height), IntRect(0, 0, viewport.width, viewport.height)))
                    SingleAxisImageTileWorkload.Mapping.NineSlice -> nineSlice(image, viewport)
                }
            return if (workload.mixed) commands + DrawCommand.FillRectangle(IntRect(0, 0, 7, 5), ArgbColor(0x80442288.toInt())) else commands
        }

        private fun nineSlice(
            image: DrawImage,
            viewport: IntSize,
        ): List<DrawCommand> {
            val xs = listOf(0, 1, viewport.width - 1, viewport.width)
            val ys = listOf(0, 1, viewport.height - 1, viewport.height)
            return buildList {
                for (row in 0..2) {
                    for (column in 0..2) add(DrawCommand.BlitImage(image, IntRect(column, row, column + 1, row + 1), IntRect(xs[column], ys[row], xs[column + 1], ys[row + 1])))
                }
            }
        }

        private fun createHost(): MinecraftUiHost = createMinecraftUiHost(definition(), profile, fontBackend = LwjglMinecraftFontBackendFactory)

        private fun definition(): UiDefinition =
            UiDefinition("Singleton-axis background fixture") {
                val image = images[source.value]
                val fitted = if (workload.mapping == SingleAxisImageTileWorkload.Mapping.Fractional) Modifier.Empty.scaleToFit(workload.design, allowUpscaling = true) else Modifier.Empty
                Stack(fitted) {
                    val background =
                        if (workload.mapping == SingleAxisImageTileWorkload.Mapping.NineSlice) {
                            Modifier.Empty.imageBackground(ImageSource.Pixels(image), Insets.all(1))
                        } else {
                            val mapping = if (workload.mapping == SingleAxisImageTileWorkload.Mapping.Stretch) ImageScale.Stretch else ImageScale.Tile
                            Modifier.Empty.imageBackground(ImageSource.Pixels(image), mapping)
                        }
                    Stack(Modifier.Empty.fillMaxSize().then(background)) {
                        if (workload.mixed) Spacer(Modifier.Empty.size(7, 5).background(ArgbColor(0x80442288.toInt())))
                    }
                }
            }

        private inline fun measured(crossinline operation: () -> RuntimeUiFrame): RuntimeUiFrame = monitor?.sample { operation() } ?: operation()

        private fun image(
            size: IntSize,
            phase: Int,
        ): DrawImage {
            val alphas = intArrayOf(0, 1, 64, 128, 254, 255)
            val equal = workload == SingleAxisImageTileWorkload.EqualMulti
            return createDrawImage(
                size,
                IntArray(size.width * size.height) { index ->
                    if (equal) 0x80112233.toInt() xor (phase * 0x00070707) else (alphas[(index + phase + 3) % alphas.size] shl 24) or ((index * 73471 + phase * 7919 + 0x123456) and 0xFFFFFF)
                },
            )
        }

        /**
         * Releases the monitor and retained host on their owner worker, including failed assertions.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                monitor?.close()
            } finally {
                monitor = null
                host.close()
            }
        }
    }

    /**
     * Generic fixture-discovery admission; no special collector flags or timing runner are added.
     */
    public companion object {
        /**
         * Validates every generated mode row and genuine callback/host/original-pixel/lifetime control before forks.
         */
        @JvmStatic
        public fun verifyWork() {
            val fixtures = listOf(SingleAxisImageBackgroundBenchmark::class.java)
            check(JmhWorkloadInventory.capture(fixtures, setOf("avgt")).size == 96)
            check(JmhWorkloadInventory.capture(fixtures, setOf("avgt", "sample")).size == 192)
            SingleAxisImageTileWorkload.entries.forEach { workload -> verify(workload) }
        }

        private fun pixels(
            frame: RuntimeUiFrame,
            density: Int = 1,
        ): IntArray {
            if (frame.size.width == 0 || frame.size.height == 0) {
                check(frame.drawCommands.isEmpty())
                return IntArray(0)
            }
            return rasterizeHeadless(frame.drawCommands, frame.size, density).copyArgb()
        }

        private fun verify(workload: SingleAxisImageTileWorkload) {
            val state = TileSession().apply { this.workload = workload }
            state.setup()
            try {
                state.monitorWork()
                val before = state.clean()
                check(state.clean() === before)
                WorkExpectation(exact = mapOf(UiRenderMetric.FrameSuccess.name to 2L, UiRenderMetric.ContentEvaluation.name to 0L, UiRenderMetric.Measure.name to 0L, UiRenderMetric.Layout.name to 0L, UiRenderMetric.Paint.name to 0L)).verify(PerformanceJson.work(state.diagnostics()))
                state.verifyPixels(before)
                val oldPixels = pixels(before)
                val work = state.producerWork()
                work.addProperty("initialFrameCommands", before.drawCommands.size)
                work.addProperty("nativeMetrics", "inapplicable-jvm-boundary")
                val changed = state.dirtySource()
                state.verifyPixels(changed)
                check(state.clean() === changed)
                WorkExpectation(minimum = mapOf(UiRenderMetric.Paint.name to 1L)).verify(PerformanceJson.work(state.diagnostics()))
                check(pixels(before).contentEquals(oldPixels))
                val resized = state.geometry()
                state.verifyPixels(resized)
                work.addProperty("replacementFrameCommands", changed.drawCommands.size)
                work.addProperty("geometryFrameCommands", resized.drawCommands.size)
                state.declaration()
                check(state.clean() === resized)
                println(work)
            } finally {
                state.close()
            }
        }
    }
}
