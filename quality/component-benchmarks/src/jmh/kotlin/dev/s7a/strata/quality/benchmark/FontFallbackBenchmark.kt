package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftBoundedFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimits
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Independent 48-case fallback corpus with repeated lookup, bounded scalar pressure and owner lifetime controls.
 * Source acquisition and snapshot loading are outside all operations; lifecycle explicitly includes engine ownership.
 */
public open class FontFallbackBenchmark {
    /**
     * Complete deterministic admission before any selected fixture starts timing.
     */
    public companion object {
        /**
         * Checks all registered cases, complete glyph parity and terminal ownership outside samples.
         */
        @JvmStatic
        public fun verifyWork(): Unit = FontFallbackWorkEvidence.verify()
    }

    /**
     * Resolves the same scalar through the prepared engine without pixel extraction.
     */
    @Benchmark
    public fun warm(state: FallbackSession): MinecraftFontGlyph = state.warm()

    /**
     * Resolves the fixed working set whose long chains exceed the ordinary raster-entry bound.
     */
    @Benchmark
    public fun churn(state: FallbackSession): MinecraftFontGlyph = state.churn()

    /**
     * Opens, queries and closes a new engine from the prepared immutable snapshot.
     */
    @Benchmark
    public fun lifecycle(state: FallbackSession): MinecraftFontGlyph = state.lifecycle()

    /**
     * One immutable source graph and owner-thread engine shared only by this JMH state.
     */
    @State(Scope.Thread)
    public open class FallbackSession {
        /**
         * Includes first, late, missing, filter, native, atlas and disabled-failure controls.
         */
        @Param("First", "Late", "Missing", "FilteredLate", "StbLate", "FreeTypeLate", "AtlasRejected", "Poisoned")
        public var workload: FontFallbackWorkload = FontFallbackWorkload.First

        /**
         * One provider and the investigation's ten-provider fallback chain.
         */
        @Param("1", "10")
        public var depth: Int = 1
        private lateinit var snapshot: MinecraftFontSnapshot
        private lateinit var engine: MinecraftFontEngine
        private val font = ResourceId("minecraft", "default")
        private var backendOpens = 0L
        private var decodes = 0L
        private var faceOpens = 0L
        private var rasters = 0L
        private var faceCloses = 0L
        private var backendCloses = 0L

        /**
         * Actual backend opens, PNG decodes, face-open attempts, native glyph calls and terminal close calls.
         * These fixture-owned counters use no clock and retain no native resource.
         */
        public val observedWork: List<Long>
            get() = listOf(backendOpens, decodes, faceOpens, rasters, faceCloses, backendCloses)

        /**
         * Actual current raster, pixel and native-face retention gauges; readable after close.
         */
        public val retained: List<Long>
            get() = listOf(engine.retainedRasterEntries.toLong(), engine.retainedRasterBytes, engine.retainedFaces.toLong())

        /**
         * Prepares every source byte and warms the selected scalar before sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            snapshot = FontFallbackAssets.snapshot(workload, depth)
            engine = createEngine(64)
            warm()
        }

        /**
         * Returns a repeated actual glyph result; the raster access order remains observable to pressure operations.
         */
        public fun warm(): MinecraftFontGlyph = engine.glyph(font, 65)

        /**
         * Exercises all 64 predetermined Unicode scalars and returns the last result to JMH.
         */
        public fun churn(): MinecraftFontGlyph = glyphs(engine)

        /**
         * Includes native preflight, cold lookup and complete owner release on a new engine.
         */
        public fun lifecycle(): MinecraftFontGlyph = createEngine(64).use(::glyphs)

        /**
         * Produces the same fixed result without raster or resolution retention for untimed pixel/metric admission.
         */
        public fun uncached(): MinecraftFontGlyph = createEngine(0).use(::glyphs)

        /**
         * Produces the same warm scalar without raster or resolution retention for untimed admission.
         */
        public fun uncachedWarm(): MinecraftFontGlyph = createEngine(0).use { it.glyph(font, 65) }

        /**
         * Captures all 64 scalar results with current, uncached and fresh bounded owners outside timing.
         * The outer order is cached churn, uncached reference and complete lifecycle.
         */
        public fun traces(): List<List<MinecraftFontGlyph>> = listOf(trace(engine), createEngine(0).use(::trace), createEngine(64).use(::trace))

        /**
         * Closes the prepared engine and requires all existing retention gauges to reach zero.
         */
        @TearDown(Level.Trial)
        public fun close() {
            engine.close()
            check(retained == listOf(0L, 0L, 0L))
        }

        private fun createEngine(entries: Int): MinecraftFontEngine =
            MinecraftFontEngine(
                snapshot,
                { compatibility ->
                    backendOpens++
                    val delegate = LwjglMinecraftFontBackendFactory.open(compatibility) as MinecraftBoundedFontBackend
                    object : MinecraftBoundedFontBackend by delegate {
                        override fun decodePng(
                            bytes: ByteArray,
                            limits: MinecraftFontLoadLimits,
                        ): DrawImage {
                            decodes++
                            return delegate.decodePng(bytes, limits)
                        }

                        override fun openTrueType(
                            bytes: ByteArray,
                            settings: MinecraftTrueTypeSettings,
                            limits: MinecraftFontLoadLimits,
                        ): MinecraftTrueTypeFace {
                            faceOpens++
                            val face = delegate.openTrueType(bytes, settings, limits)
                            return object : MinecraftTrueTypeFace by face {
                                override fun glyph(codePoint: Int): MinecraftFontGlyph? {
                                    rasters++
                                    return face.glyph(codePoint)
                                }

                                override fun close() {
                                    faceCloses++
                                    face.close()
                                }
                            }
                        }

                        override fun close() {
                            backendCloses++
                            delegate.close()
                        }
                    }
                },
                cacheEntries = entries,
                cacheBytes = 1024 * 1024,
                maxFaces = 1,
            )

        private fun trace(owner: MinecraftFontEngine): List<MinecraftFontGlyph> = (65..128).map { scalar -> owner.glyph(font, scalar) }

        private fun glyphs(owner: MinecraftFontEngine): MinecraftFontGlyph {
            var glyph = owner.glyph(font, 65)
            for (scalar in 66..128) glyph = owner.glyph(font, scalar)
            return glyph
        }
    }
}
