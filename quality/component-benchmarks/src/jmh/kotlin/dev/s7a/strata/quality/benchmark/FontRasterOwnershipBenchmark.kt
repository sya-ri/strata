package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontBackend
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeFace
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeSettings
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.nio.file.Files
import java.nio.file.Path

/**
 * Complete cold font-raster construction and unchanged warm/lifecycle controls through actual runtime APIs.
 * PNG encoding, source loading, native face creation and all reference checks are outside raster samples.
 */
public open class FontRasterOwnershipBenchmark {
    /**
     * Untimed whole-fixture parity, generated matrix and owner-release verification.
     */
    public companion object {
        /**
         * Verifies the complete independent corpus without a timing threshold.
         */
        @JvmStatic
        public fun verifyWork(): Unit = FontRasterWorkEvidence.verify()
    }

    /**
     * Includes native PNG decode and conversion, with encoded input already prepared.
     */
    @Benchmark
    public fun decodePng(state: PngSession): DrawImage = state.decode()

    /**
     * Isolates cell construction from PNG decoding using a prepared immutable source sheet.
     */
    @Benchmark
    public fun bitmapCell(state: BitmapSession): MinecraftFontGlyph = state.glyph()

    /**
     * Constructs uncached pixels from an already loaded hexadecimal provider.
     */
    @Benchmark
    public fun unihex(state: UnihexSession): MinecraftFontGlyph = state.glyph()

    /**
     * Includes native rasterization and pixel conversion from an already opened face.
     */
    @Benchmark
    public fun trueType(state: TrueTypeSession): MinecraftFontGlyph = state.glyph()

    /**
     * Preserves the existing provider fixture's warm lookup as a zero-copy-opportunity control.
     */
    @Benchmark
    public fun warm(state: ControlSession): MinecraftFontGlyph = state.warm()

    /**
     * Preserves the existing complete independently owned provider lifecycle fixture.
     */
    @Benchmark
    public fun lifecycle(state: ControlSession): MinecraftFontGlyph = state.lifecycle()

    /**
     * Prepared PNG bytes and one owner-thread backend; each sampled decode returns a detached image.
     */
    @State(Scope.Thread)
    public open class PngSession {
        /**
         * Square source dimensions, including a representative one-megapixel sheet.
         */
        @Param("32", "256", "1024")
        public var axis: Int = 32
        private lateinit var bytes: ByteArray
        private lateinit var backend: MinecraftFontBackend

        /**
         * Prepares or reads the frozen bytes before any sampled decode.
         */
        @Setup(Level.Trial)
        public fun setup() {
            bytes = FontRasterAssets.png(axis)
            backend = LwjglMinecraftFontBackendFactory.open(FontRasterAssets.compatibility(MinecraftTrueTypeRasterizer.FreeType))
        }

        /**
         * Returns the actual decoder result without extracting or hashing its pixels inside timing.
         */
        public fun decode(): DrawImage = backend.decodePng(bytes)

        /**
         * Closes the independently owned backend.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = backend.close()
    }

    /**
     * Actual cell metrics and image construction with disabled raster caches and a predecoded source.
     */
    @State(Scope.Thread)
    public open class BitmapSession {
        /**
         * Cell extent; 257 is the no-cell-array atlas rejection control.
         */
        @Param("8", "64", "256", "257")
        public var cell: Int = 8
        private lateinit var engine: MinecraftFontEngine
        private var decodeCount = 0
        private var closeCount = 0

        /**
         * Actual calls to the prepared source backend, available only to untimed work validation.
         */
        public val decodes: Int get() = decodeCount

        /**
         * Loads the provider and prepared sheet before sampling; it does not cache a raster.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val sheet = FontRasterAssets.sheet(cell)
            val snapshot = FontRasterAssets.bitmap(cell)
            engine = MinecraftFontEngine(snapshot, { preparedBackend(sheet) }, cacheEntries = 0, cacheBytes = 0)
            glyph()
        }

        /**
         * Reconstructs the actual cell result and metrics from the same immutable source.
         */
        public fun glyph(): MinecraftFontGlyph = engine.glyph(ResourceId("minecraft", "default"), 65)

        /**
         * Releases the engine and checks terminal raster/backend ownership.
         */
        @TearDown(Level.Trial)
        public fun close() {
            engine.close()
            check(engine.retainedRasterEntries == 0 && engine.retainedRasterBytes == 0L && closeCount == 1)
        }

        private fun preparedBackend(sheet: DrawImage): MinecraftFontBackend =
            object : MinecraftFontBackend {
                override fun decodePng(bytes: ByteArray): DrawImage {
                    decodeCount++
                    return sheet
                }

                override fun openTrueType(
                    bytes: ByteArray,
                    settings: MinecraftTrueTypeSettings,
                ): MinecraftTrueTypeFace = error("The bitmap fixture has no TrueType provider")

                override fun close() {
                    closeCount++
                }
            }
    }

    /**
     * Already loaded Unihex definitions with no retained glyph/raster results.
     */
    @State(Scope.Thread)
    public open class UnihexSession {
        /**
         * Inclusive ink width of a sixteen-row source.
         */
        @Param("8", "16", "32")
        public var width: Int = 8
        private lateinit var engine: MinecraftFontEngine

        /**
         * Loads the immutable hexadecimal source before sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            engine = MinecraftFontEngine(FontRasterAssets.unihex(width), LwjglMinecraftFontBackendFactory, cacheEntries = 0, cacheBytes = 0)
            glyph()
        }

        /**
         * Constructs one actual uncached glyph and its detached pixels.
         */
        public fun glyph(): MinecraftFontGlyph = engine.glyph(ResourceId("minecraft", "default"), 65)

        /**
         * Releases all engine/native references and requires no retained rasters.
         */
        @TearDown(Level.Trial)
        public fun close() {
            engine.close()
            check(engine.retainedRasterEntries == 0 && engine.retainedRasterBytes == 0L && engine.retainedFaces == 0)
        }
    }

    /**
     * One actual native face, with rasterization repeated for each sample without an engine cache.
     */
    @State(Scope.Thread)
    public open class TrueTypeSession {
        /**
         * Native provider contract; results are compared within each provider, never between providers.
         */
        @Param("Stb", "FreeType")
        public lateinit var rasterizer: MinecraftTrueTypeRasterizer

        /**
         * Size and oversampling keep both native rasters inside the existing atlas bound.
         */
        @Param("11", "96")
        public var size: Int = 11
        private lateinit var backend: MinecraftFontBackend
        private lateinit var face: MinecraftTrueTypeFace

        /**
         * Loads the exact registered CC0 input and opens the native face outside timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val bytes = Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))))
            backend = LwjglMinecraftFontBackendFactory.open(FontRasterAssets.compatibility(rasterizer))
            face = backend.openTrueType(bytes, MinecraftTrueTypeSettings(size.toFloat(), 2f, 0.25f, -0.5f))
            checkNotNull(glyph().image)
        }

        /**
         * Invokes the real native rasterizer and returns the fully converted immutable result.
         */
        public fun glyph(): MinecraftFontGlyph = checkNotNull(face.glyph(65))

        /**
         * Backend closure releases the face; previously returned glyphs remain detached.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = backend.close()
    }

    /**
     * Borrows the unchanged original font-provider fixture for matched controls.
     */
    @State(Scope.Thread)
    public open class ControlSession {
        /**
         * Cached bitmap, Unihex and both registered native provider fixtures.
         */
        @Param("BitmapCached", "UnihexCached", "StbCached", "FreeTypeCached")
        public lateinit var workload: FontWorkload
        private lateinit var owner: FontProviderBenchmark.FontSession

        /**
         * Runs the original source preparation and warm request before sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val selected = workload
            owner = FontProviderBenchmark.FontSession().apply { workload = selected }
            owner.setup()
        }

        /**
         * Delegates the unchanged cached lookup operation.
         */
        public fun warm(): MinecraftFontGlyph = owner.warm()

        /**
         * Delegates the unchanged independently owned lifecycle operation.
         */
        public fun lifecycle(): MinecraftFontGlyph = owner.lifecycle()

        /**
         * Verifies the original terminal raster/face counters.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = owner.close()
    }
}
