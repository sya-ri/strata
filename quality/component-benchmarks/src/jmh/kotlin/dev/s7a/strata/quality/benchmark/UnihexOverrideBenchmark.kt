package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonObject
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.RuntimeWorkMonitor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.minecraft.MinecraftUiHost
import dev.s7a.strata.runtime.minecraft.MinecraftUiProfile
import dev.s7a.strata.runtime.minecraft.createMinecraftUiHost
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.ui.UiDefinition
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Complete public glyph batches and independent setup, retention, replacement and actual Text frame costs.
 * Warm glyph and clean host controls retain their unchanged work; no native GPU timing is inferred.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class UnihexOverrideBenchmark {
    /**
     * Checks the complete frozen matrix outside timing.
     */
    public companion object {
        /**
         * Independent pixel, metric, input and ownership admission.
         */
        @JvmStatic
        public fun verifyWork(): Unit = UnihexOverrideWorkEvidence.verify()
    }

    /**
     * Complete prepared query batch, including uncached or evicted raster construction.
     */
    @Benchmark
    public fun glyphs(state: OverrideSession): MinecraftFontGlyph = state.glyphs()

    /**
     * One prepared scalar, including the fully warm cache control.
     */
    @Benchmark
    public fun warm(state: OverrideSession): MinecraftFontGlyph = state.warm()

    /**
     * New engine, one cold glyph and close; includes first-use construction and ownership.
     */
    @Benchmark
    public fun firstUse(state: OverrideSession): MinecraftFontGlyph = state.firstUse()

    /**
     * Complete public snapshot load from prepared source bytes.
     */
    @Benchmark
    public fun load(state: OverrideSession): MinecraftFontSnapshot = state.load()

    /**
     * New engine, complete query batch and close.
     */
    @Benchmark
    public fun lifecycle(state: OverrideSession): MinecraftFontGlyph = state.lifecycle()

    /**
     * Unchanged retained Text frame without input or time changes.
     */
    @Benchmark
    public fun cleanFrame(state: OverrideSession): RuntimeUiFrame = state.cleanFrame()

    /**
     * Real source revision, Text layout and current frame extraction on a retained host.
     */
    @Benchmark
    public fun dirtyFrame(state: OverrideSession): RuntimeUiFrame = state.dirtyFrame()

    /**
     * Independent public host open, attach, first Text frame and close.
     */
    @Benchmark
    public fun textLifecycle(state: OverrideSession): RuntimeUiFrame = state.textLifecycle()

    /**
     * New snapshot and profile, independent host, first Text frame and close.
     */
    @Benchmark
    public fun replaceSnapshot(state: OverrideSession): RuntimeUiFrame = state.replaceSnapshot()

    /**
     * Fixed immutable inputs and independent worker-owned engine/host.
     */
    @State(Scope.Thread)
    public open class OverrideSession {
        /**
         * Every synthetic range, admission, pressure and unchanged-work control.
         */
        @Param
        public var scenario: UnihexOverrideScenario = UnihexOverrideScenario.Last
        private lateinit var source: MinecraftMemoryFontAssetSource
        private lateinit var snapshot: MinecraftFontSnapshot
        private lateinit var profile: MinecraftUiProfile
        private lateinit var engine: MinecraftFontEngine
        private lateinit var host: MinecraftUiHost
        private lateinit var scalars: List<Int>
        private lateinit var textSource: StressStateSource<String>
        private lateinit var texts: List<String>
        private var phase = 0
        private var monitor: RuntimeWorkMonitor? = null
        private val font = ResourceId("minecraft", "default")
        private val viewport = IntSize(320, 80)

        /**
         * Source bytes, snapshots, Text values and all warmed state precede sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            source = UnihexOverrideAssets.source(scenario)
            snapshot = load()
            profile = ComponentProfile.create(snapshot)
            scalars = UnihexOverrideAssets.scalars(scenario)
            texts = listOf(scalars, scalars.asReversed()).map { points -> points.joinToString("") { String(Character.toChars(it)) } }
            textSource = StressStateSource(texts.first())
            engine = fresh()
            glyphs()
            host = freshHost(profile)
            host.attach()
            cleanFrame()
            dirtyFrame()
            dirtyFrame()
        }

        /**
         * Resolves each prepared scalar once in its fixed order.
         */
        public fun glyphs(): MinecraftFontGlyph = batch(engine)

        /**
         * Prepared first scalar lookup; no detached pixel extraction.
         */
        public fun warm(): MinecraftFontGlyph = engine.glyph(font, scalars.first())

        /**
         * Complete first-use engine lifetime with exactly one query.
         */
        public fun firstUse(): MinecraftFontGlyph = fresh().use { it.glyph(font, scalars.first()) }

        /**
         * Actual snapshot loading; fixture source construction is excluded.
         */
        public fun load(): MinecraftFontSnapshot = UnihexOverrideAssets.load(source)

        /**
         * Fresh bounded owner lifetime with the complete fixed query batch.
         */
        public fun lifecycle(): MinecraftFontGlyph = fresh().use(::batch)

        /**
         * Independent uncached complete trace for untimed parity.
         */
        public fun trace(): List<MinecraftFontGlyph> = fresh().use { owner -> scalars.map { owner.glyph(font, it) } }

        /**
         * Current raster, pixel and face ownership gauges, including terminal zero.
         */
        public val retained: List<Long> get() = listOf(engine.retainedRasterEntries.toLong(), engine.retainedRasterBytes, engine.retainedFaces.toLong())

        /**
         * Actual source subscriptions owned by current host and temporary scoped hosts.
         */
        public val subscriptions: Int get() = textSource.subscriptions

        /**
         * Enables bounded diagnostics for untimed admission only.
         */
        public fun monitorWork() {
            check(monitor == null)
            monitor = RuntimeWorkMonitor(host, checkpointSamples = 16, maxNodeRecords = 64)
        }

        /**
         * Returns detached counters collected only when admission explicitly enabled diagnostics.
         */
        public val diagnostics: JsonObject get() = checkNotNull(monitor).snapshot()

        /**
         * Current clean retained frame at unchanged time.
         */
        public fun cleanFrame(): RuntimeUiFrame = frame { host.frame(viewport, FrameTime(0)) }

        /**
         * Alternates actual source text and extracts the resulting dirty frame.
         */
        public fun dirtyFrame(): RuntimeUiFrame = frame {
            phase = 1 - phase
            textSource.publish(texts[phase])
            host.frame(viewport, FrameTime(0))
        }

        /**
         * Detached first Text frame from an independently closed host.
         */
        public fun textLifecycle(): RuntimeUiFrame = extract(profile)

        /**
         * Replaces immutable snapshot/profile ownership without modifying the retained host.
         */
        public fun replaceSnapshot(): RuntimeUiFrame = extract(ComponentProfile.create(load()))

        /**
         * Closes every retained owner and checks terminal cache/subscription release.
         */
        @TearDown(Level.Trial)
        public fun close() {
            try {
                monitor?.close()
            } finally {
                monitor = null
                try {
                    host.close()
                } finally {
                    engine.close()
                }
            }
            check(retained == listOf(0L, 0L, 0L) && subscriptions == 0)
        }

        private fun fresh(): MinecraftFontEngine = MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory, cacheEntries = scenario.cacheEntries, cacheBytes = 1024 * 1024, maxFaces = 1)

        private fun batch(owner: MinecraftFontEngine): MinecraftFontGlyph {
            var glyph = owner.glyph(font, scalars.first())
            for (index in 1 until scalars.size) glyph = owner.glyph(font, scalars[index])
            return glyph
        }

        private fun freshHost(selected: MinecraftUiProfile): MinecraftUiHost = createMinecraftUiHost(UiDefinition("Ordered Unihex text") { Observe(textSource) { Text(it, TextLayout.Multiline()) } }, selected, fontBackend = LwjglMinecraftFontBackendFactory)

        private fun extract(selected: MinecraftUiProfile): RuntimeUiFrame = freshHost(selected).use { owner ->
            owner.attach()
            owner.frame(viewport, FrameTime(0))
        }

        private inline fun frame(crossinline operation: () -> RuntimeUiFrame): RuntimeUiFrame = monitor?.sample { operation() } ?: operation()
    }
}
