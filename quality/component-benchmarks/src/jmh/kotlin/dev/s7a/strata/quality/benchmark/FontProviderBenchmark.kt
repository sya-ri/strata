package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Real provider, cache, reference-boundary and native-face work with the standard JMH lifecycle.
 * Source preparation and fixture validation are outside all sampled operations.
 */
public open class FontProviderBenchmark {
    /**
     * Resolves the already requested glyph through the retained engine.
     */
    @Benchmark
    public fun warm(state: FontSession): MinecraftFontGlyph = state.warm()

    /**
     * Traverses the declared glyph/font working set, including bounded cache and face replacement.
     */
    @Benchmark
    public fun churn(state: FontSession): MinecraftFontGlyph = state.churn()

    /**
     * Loads prepared source bytes; this separate phase never includes PNG encoding or file acquisition.
     */
    @Benchmark
    public fun load(state: FontSession): MinecraftFontSnapshot = state.load()

    /**
     * Opens, uses and closes an independent native font engine against one immutable snapshot.
     */
    @Benchmark
    public fun lifecycle(state: FontSession): MinecraftFontGlyph = state.lifecycle()

    /**
     * Owns one worker's source, detached snapshot and native engine, with explicit terminal retention checks.
     */
    @State(Scope.Thread)
    public open class FontSession {
        /**
         * Exact provider contract supplied by the standard generated registry.
         */
        @Param
        public lateinit var workload: FontWorkload
        private lateinit var source: MinecraftMemoryFontAssetSource
        private lateinit var snapshot: MinecraftFontSnapshot
        private lateinit var engine: MinecraftFontEngine
        private var fonts = listOf(ResourceId("minecraft", "default"))
        private var scalars = listOf(65)

        /**
         * Finite raster bound used by this scenario, independent of snapshot input ceilings.
         */
        public val cacheEntries: Int
            get() = if (workload in setOf(FontWorkload.BitmapUncached, FontWorkload.UnihexUncached)) 0 else 64

        /**
         * Native face bound deliberately exceeded by the 17-descriptor churn scenarios.
         */
        public val faceLimit: Int
            get() = if (workload == FontWorkload.StbFaces1) 1 else 16

        /**
         * Read-only retention gauges for untimed correctness checks.
         */
        public val retained: List<Long>
            get() = listOf(engine.retainedRasterEntries.toLong(), engine.retainedRasterBytes, engine.retainedFaces.toLong())

        /**
         * Creates source bytes and resolves the first glyph before benchmark execution.
         */
        @Setup(Level.Trial)
        public fun setup() {
            source = FontPerformanceAssets.source(workload)
            snapshot = load()
            val defaultFont = ResourceId("minecraft", "default")
            if (workload == FontWorkload.ReferenceDepth129) {
                check(defaultFont !in snapshot.fontIds && snapshot.diagnostics.isNotEmpty())
            } else {
                check(defaultFont in snapshot.fontIds && snapshot.diagnostics.isEmpty())
            }
            val native = workload in setOf(FontWorkload.StbCached, FontWorkload.FreeTypeCached, FontWorkload.StbFaces1, FontWorkload.FreeTypeFaces16)
            scalars =
                if (native) {
                    listOf(65, 0x65e5, 0xd55c, 0x1f642)
                } else if (workload in setOf(FontWorkload.ReferenceDepth128, FontWorkload.ReferenceDepth129)) {
                    listOf(65)
                } else {
                    (32..126).toList()
                }
            if (workload in setOf(FontWorkload.StbFaces1, FontWorkload.FreeTypeFaces16)) fonts = listOf(defaultFont) + (1..16).map { ResourceId("strata_benchmark", "face_$it") }
            engine = createEngine()
            warm()
        }

        /**
         * Reuses the same valid non-space scalar in the default font.
         */
        public fun warm(): MinecraftFontGlyph = engine.glyph(fonts.first(), 65)

        /**
         * Resolves the complete working set and returns the last actual result to JMH's blackhole.
         */
        public fun churn(): MinecraftFontGlyph = glyphs(engine)

        /**
         * Reloads one source snapshot without opening a native engine or acquiring source files.
         */
        public fun load(): MinecraftFontSnapshot = FontPerformanceAssets.snapshot(workload, source)

        /**
         * Independently owns and closes the native engine inside this explicitly timed lifetime operation.
         */
        public fun lifecycle(): MinecraftFontGlyph = createEngine().use { owner -> glyphs(owner) }

        /**
         * Releases all native faces, retained pixels and snapshot references; returned glyphs stay detached.
         */
        @TearDown(Level.Trial)
        public fun close() {
            engine.close()
            check(retained == listOf(0L, 0L, 0L))
        }

        private fun createEngine(): MinecraftFontEngine = MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory, cacheEntries = cacheEntries, cacheBytes = 1024 * 1024, maxFaces = faceLimit)

        private fun glyphs(owner: MinecraftFontEngine): MinecraftFontGlyph {
            var last = owner.glyph(fonts.first(), 65)
            fonts.forEach { font -> scalars.forEach { scalar -> last = owner.glyph(font, scalar) } }
            return last
        }
    }
}
