package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown

/**
 * Whole grayscale corpus: twenty actual-converter cases, eight complete glyph/Text rows and twelve controls.
 * Uses the ordinary generated JMH inventory and shared collector; no fixture-specific build registration exists.
 * Every collection must first validate and freeze native admission for its exact common runtime foundation.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class FreeTypeGrayscaleBenchmark {
    /**
     * Generic discovery invokes the complete untimed verifier before collection and during ordinary checks.
     */
    public companion object {
        /**
         * Verifies the complete corpus outside timing; does not write or replace a native admission receipt.
         */
        @JvmStatic
        public fun verifyWork(): Unit = FreeTypeGrayscaleWorkEvidence.verify()
    }

    /**
     * Times the real private runtime converter including its identical reflective adapter and owned image.
     */
    @Benchmark
    public fun converter(state: ConverterSession): DrawImage = state.image()

    /**
     * Includes the original native measure, prospective inspection, render, conversion and exact glyph metrics.
     */
    @Benchmark
    public fun completeFreeTypeGlyph(state: GlyphSession): MinecraftFontGlyph = state.glyph()

    /**
     * Resets through a fresh real host, then attaches, extracts one dirty Text/profile frame and closes it.
     * This includes cold native owner/face creation and terminal release; no source acquisition is sampled.
     */
    @Benchmark
    public fun completeDirtyTextFrame(state: GlyphSession): RuntimeUiFrame = state.dirtyText()

    /**
     * Keeps all unchanged, rejected and lifecycle boundaries, including slower and no-benefit outcomes.
     */
    @Benchmark
    public fun control(state: ControlSession): Any = state.sample()

    /**
     * Signed physical row layout of an explicitly supplied native bitmap, independent of real font output.
     *
     * @property padded whether each source row includes three poisoned trailing bytes.
     * @property reversed whether logical top-to-bottom rows use negative native pitch.
     */
    public enum class Layout(
        public val padded: Boolean,
        public val reversed: Boolean,
    ) {
        TightPositive(false, false),
        PaddedPositive(true, false),
        TightNegative(false, true),
        PaddedNegative(true, true),
    }

    /**
     * Required single-row controls with complete boundaries supplied by [FreeTypeControlFixture].
     */
    public enum class Control {
        WarmFreeTypeRaster,
        MissingFreeTypeGlyph,
        EmptyFreeTypeGlyph,
        AtlasRejectedGlyph,
        ImageLimitRejectedGlyph,
        StbGlyph,
        BitmapGlyph,
        SnapshotLoad,
        EngineLifecycle,
        SnapshotReplacement,
        CompleteCleanTextFrame,
        MalformedConverterInput,
    }

    /**
     * Owns native input once per worker trial; each call creates exactly one detached output image.
     */
    @State(Scope.Thread)
    public open class ConverterSession {
        /**
         * All specified square converter extents, including the exact native atlas axis boundary.
         */
        @Param("1", "8", "16", "64", "256")
        public var axis: Int = 1

        /**
         * All tight/padded and positive/negative source pitches.
         */
        @Param
        public lateinit var layout: Layout
        private lateinit var owner: FreeTypeBitmapFixture

        /**
         * Prepares native storage and the actual runtime adapter outside sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            FreeTypeGrayscaleAssets.requireAdmission()
            owner = FreeTypeBitmapFixture(axis, layout)
        }

        /**
         * Executes the actual converter without oracle extraction inside timing.
         */
        public fun image(): DrawImage = owner.image()

        /**
         * Frees the trial's bitmap, source bytes and native face after all samples.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = owner.close()
    }

    /**
     * Prepared immutable source/profile and one native face for complete glyph calls.
     */
    @State(Scope.Thread)
    public open class GlyphSession {
        /**
         * Four real native inputs validated independently before their measurement freeze.
         */
        @Param
        public lateinit var fixture: FreeTypeGlyphFixture
        private lateinit var owner: FreeTypeGlyphOwner

        /**
         * Acquires source bytes and profile once, and requires a real nonempty admitted glyph.
         */
        @Setup(Level.Trial)
        public fun setup() {
            FreeTypeGrayscaleAssets.requireAdmission()
            owner = FreeTypeGlyphOwner(fixture)
        }

        /**
         * Returns the fully constructed genuine native glyph with no engine raster cache.
         */
        public fun glyph(): MinecraftFontGlyph = owner.glyph()

        /**
         * Executes the complete documented dirty/reset boundary, including owner cleanup.
         */
        public fun dirtyText(): RuntimeUiFrame = owner.dirtyText()

        /**
         * Releases trial-native ownership; already returned glyphs and frames remain detached.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = owner.close()
    }

    /**
     * Independent prepared owners for the twelve required original-work controls.
     */
    @State(Scope.Thread)
    public open class ControlSession {
        /**
         * One control per comparison row, never filtered by conversion eligibility.
         */
        @Param
        public lateinit var operation: Control
        private lateinit var owner: FreeTypeControlFixture

        /**
         * Builds only the selected control's source and owners outside sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            FreeTypeGrayscaleAssets.requireAdmission()
            owner = FreeTypeControlFixture(operation)
        }

        /**
         * Executes the whole original control boundary through its runtime API.
         */
        public fun sample(): Any = owner.sample()

        /**
         * Closes every trial resource and retains no output history.
         */
        @TearDown(Level.Trial)
        public fun close(): Unit = owner.close()
    }
}
