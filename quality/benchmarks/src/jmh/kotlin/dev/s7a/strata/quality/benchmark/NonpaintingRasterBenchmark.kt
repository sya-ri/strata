package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadlessInto
import dev.s7a.strata.runtime.headless.rasterizeHeadlessRegion
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Independent finite raster corpus for nonpainting dispatch and visible guard-overhead controls.
 * Immutable inputs, dirty borrowed-storage reset and scalar acceptance run outside measured operations.
 * Immutable output includes fresh image/storage ownership; borrowed output includes required prefix initialization only.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class NonpaintingRasterBenchmark {
    /**
     * Produces a newly owned complete region image; no output copy or PNG encoding is included.
     */
    @Benchmark
    public fun immutable(state: Pixels): HeadlessImage = state.immutable()

    /**
     * Initializes and paints an existing dirty prefix without touching excess capacity or retaining a new image.
     */
    @Benchmark
    public fun borrowed(state: Pixels): IntArray = state.borrowed()

    /**
     * Owns one fixed command list, immutable source and reusable destination per JMH worker.
     * Original-coordinate regions deliberately start away from zero; final-density sampling remains absolute.
     */
    @State(Scope.Thread)
    public open class Pixels {
        /**
         * Fixed coverage, continuation, density, threshold and visible-control combinations; no Cartesian expansion.
         */
        @JvmField
        @Param
        public var scenario: Scenario = Scenario.Empty

        private lateinit var bounds: IntRect
        private lateinit var commands: List<DrawCommand>
        private lateinit var pixels: IntArray
        private var area = 0

        /**
         * Builds detached patterned inputs and validates the complete scalar result before timing begins.
         */
        @Setup(Level.Trial)
        @Suppress("LongMethod", "CyclomaticComplexMethod") // Keep the finite command corpus and scalar acceptance in the same untimed preparation boundary.
        public fun setup() {
            bounds = IntRect(12, 10, 12 + scenario.width, 10 + scenario.height)
            area = scenario.width * scenario.height * scenario.scale * scenario.scale
            val image = createDrawImage(IntSize(3, 2), intArrayOf(0x001337AA, -1, 0x80445566.toInt(), 0x01020406, 0x407799BB, 0xFF224466.toInt()))
            val source = IntRect(0, 0, 3, 2)
            val destination =
                when (scenario.coverage) {
                    Coverage.Offscreen -> IntRect(bounds.right, bounds.top, bounds.right + 3, bounds.bottom)
                    Coverage.OnePixel -> IntRect(bounds.left, bounds.top, bounds.left + 1, bounds.top + 1)
                    else -> bounds
                }
            val fractional =
                if (scenario.coverage == Coverage.Subpixel) {
                    FloatRect(12f, 10f, 12f + 0.25f / scenario.scale, 10f + 0.25f / scenario.scale)
                } else {
                    FloatRect(destination.left.toFloat(), destination.top.toFloat(), destination.right.toFloat(), destination.bottom.toFloat())
                }
            val primitives =
                listOf(
                    DrawCommand.FillRectangle(destination, ArgbColor(0x80345678.toInt())),
                    DrawCommand.BlitImage(image, source, destination),
                    DrawCommand.BlitImagePixels(image, source, destination),
                    DrawCommand.SampledImage(image, FloatRect(0.125f, 0.0625f, 2.875f, 1.9375f), fractional, ArgbColor(if (scenario.coverage == Coverage.ZeroTint) 0x001337AA else 0x807193B5.toInt()), 0.001f),
                )
            val selected =
                when (scenario.primitive) {
                    Primitive.Fill -> listOf(primitives[0])
                    Primitive.Logical -> listOf(primitives[1])
                    Primitive.Physical -> listOf(primitives[2])
                    Primitive.Sampled -> listOf(primitives[3])
                    Primitive.Mixed -> primitives
                    Primitive.Empty -> emptyList()
                }
            val clipped =
                when (scenario.coverage) {
                    Coverage.EmptyClip -> {
                        listOf(DrawCommand.PushClip(IntRect(12, 10, 12, bounds.bottom))) + selected + DrawCommand.PopClip
                    }

                    Coverage.FractionalClip -> {
                        listOf(
                            DrawCommand.PushClip(bounds),
                            DrawCommand.PushFractionalClip(FloatRect(12f, 10f, 12f + 0.25f / scenario.scale, 10f + 0.25f / scenario.scale)),
                        ) + selected + listOf(DrawCommand.PopClip, DrawCommand.PopClip)
                    }

                    else -> {
                        selected
                    }
                }
            val prefix = if (scenario.patterned) listOf(DrawCommand.BlitImage(image, source, bounds)) else emptyList()
            val suffix =
                when (scenario.continuation) {
                    Continuation.None -> emptyList()
                    Continuation.Opaque -> listOf(DrawCommand.FillRectangle(bounds, ArgbColor(0xFF345678.toInt())))
                    Continuation.SingleTranslucent -> listOf(DrawCommand.FillRectangle(bounds, ArgbColor(0x80345678.toInt())))
                    Continuation.Translucent -> listOf(DrawCommand.FillRectangle(bounds, ArgbColor(0x80345678.toInt())), DrawCommand.FillRectangle(bounds, ArgbColor(0x407799BB)))
                }
            commands = prefix + clipped + suffix
            pixels = IntArray(area + 17) { 0x001337AA }
            val expected = NonpaintingRasterReference.paint(commands, bounds, scenario.scale)
            check(immutable().copyArgb().contentEquals(expected)) { "Immutable scalar mismatch: $scenario" }
            check(borrowed().copyOf(area).contentEquals(expected)) { "Borrowed scalar mismatch: $scenario" }
            check(pixels.takeLast(17).all { it == 0x001337AA }) { "Borrowed tail changed: $scenario" }
            check(image.copyArgb().contentEquals(intArrayOf(0x001337AA, -1, 0x80445566.toInt(), 0x01020406, 0x407799BB, 0xFF224466.toInt())))
        }

        /**
         * Restores dirty storage outside timing without allocation; applies identically on both runtime sides.
         * This warms the borrowed prefix, which must be disclosed when comparing the separate ownership methods.
         */
        @Setup(Level.Invocation)
        public fun resetBorrowed() {
            pixels.fill(0x001337AA)
        }

        /**
         * Returns fresh immutable output with every required pixel initialized.
         */
        public fun immutable(): HeadlessImage = rasterizeHeadlessRegion(commands, bounds, scenario.scale)

        /**
         * Returns the existing destination after a complete paint operation; excess storage remains caller-owned.
         */
        public fun borrowed(): IntArray {
            rasterizeHeadlessInto(commands, bounds, scenario.scale, pixels)
            return pixels
        }
    }

    /**
     * Primitive selection uses detached command variants rather than external name comparisons.
     */
    public enum class Primitive {
        Empty,
        Fill,
        Logical,
        Physical,
        Sampled,
        Mixed,
    }

    /**
     * Physical coverage and zero-tint controls, independent of immutable image content.
     */
    public enum class Coverage {
        Offscreen,
        EmptyClip,
        FractionalClip,
        Subpixel,
        ZeroTint,
        Visible,
        OnePixel,
    }

    /**
     * Ordered full-region fills following the selected primitive, with per-layer rounding intact.
     */
    public enum class Continuation {
        None,
        Opaque,
        SingleTranslucent,
        Translucent,
    }

    /**
     * Complete bounded evidence cases; physical area is width times height times scale squared.
     * Palette boundaries are 4,096 and 262,144 pixels; a single full-fill lookup starts at 1,048,576 pixels.
     * The three 262,144 cases use two later fills, while the other boundary cases use one.
     *
     * @property primitive detached command kind or mixed/empty list.
     * @property coverage physical invisibility or visible-control geometry.
     * @property continuation subsequent ordered full-region composition.
     * @property width logical output width.
     * @property height logical output height.
     * @property scale final physical density.
     * @property patterned whether a nonuniform image precedes the selected command.
     */
    public enum class Scenario(
        public val primitive: Primitive,
        public val coverage: Coverage,
        public val continuation: Continuation,
        public val width: Int = 64,
        public val height: Int = 64,
        public val scale: Int = 1,
        public val patterned: Boolean = false,
    ) {
        Empty(Primitive.Empty, Coverage.Visible, Continuation.None),
        FillOffscreen(Primitive.Fill, Coverage.Offscreen, Continuation.SingleTranslucent, 1024, 1024),
        LogicalOffscreen(Primitive.Logical, Coverage.Offscreen, Continuation.SingleTranslucent, 1024, 1024),
        PhysicalOffscreen(Primitive.Physical, Coverage.Offscreen, Continuation.SingleTranslucent, 1024, 1024),
        SampledOffscreen(Primitive.Sampled, Coverage.Offscreen, Continuation.SingleTranslucent, 1024, 1024),
        FillEmptyClip(Primitive.Fill, Coverage.EmptyClip, Continuation.None),
        LogicalEmptyClip(Primitive.Logical, Coverage.EmptyClip, Continuation.None, scale = 2),
        PhysicalEmptyClip(Primitive.Physical, Coverage.EmptyClip, Continuation.None, scale = 3),
        SampledEmptyClip(Primitive.Sampled, Coverage.EmptyClip, Continuation.None, scale = 4),
        FillFractionalClip(Primitive.Fill, Coverage.FractionalClip, Continuation.Translucent),
        LogicalFractionalClip(Primitive.Logical, Coverage.FractionalClip, Continuation.Translucent, scale = 2),
        PhysicalFractionalClip(Primitive.Physical, Coverage.FractionalClip, Continuation.Translucent, scale = 3),
        SampledFractionalClip(Primitive.Sampled, Coverage.FractionalClip, Continuation.Translucent, scale = 4),
        SampledSubpixel(Primitive.Sampled, Coverage.Subpixel, Continuation.Translucent, scale = 3),
        SampledZeroTint(Primitive.Sampled, Coverage.ZeroTint, Continuation.Translucent, scale = 4),
        MixedNone(Primitive.Mixed, Coverage.Offscreen, Continuation.None, scale = 2),
        MixedOpaque(Primitive.Mixed, Coverage.Offscreen, Continuation.Opaque, scale = 3),
        MixedTranslucent(Primitive.Mixed, Coverage.Offscreen, Continuation.Translucent, scale = 4),
        PatternedNonpainting(Primitive.Mixed, Coverage.FractionalClip, Continuation.Translucent, scale = 2, patterned = true),
        FillVisible(Primitive.Fill, Coverage.Visible, Continuation.None, scale = 4, patterned = true),
        LogicalVisible(Primitive.Logical, Coverage.Visible, Continuation.None, scale = 3, patterned = true),
        PhysicalVisible(Primitive.Physical, Coverage.Visible, Continuation.None, scale = 2, patterned = true),
        SampledVisible(Primitive.Sampled, Coverage.Visible, Continuation.None, patterned = true),
        FillOnePixel(Primitive.Fill, Coverage.OnePixel, Continuation.None),
        LogicalOnePixel(Primitive.Logical, Coverage.OnePixel, Continuation.None),
        PhysicalOnePixel(Primitive.Physical, Coverage.OnePixel, Continuation.None),
        SampledOnePixel(Primitive.Sampled, Coverage.OnePixel, Continuation.None),
        BelowPalette(Primitive.Fill, Coverage.Offscreen, Continuation.SingleTranslucent, 4095, 1),
        AtPalette(Primitive.Fill, Coverage.Offscreen, Continuation.SingleTranslucent, 4096, 1),
        AbovePalette(Primitive.Fill, Coverage.Offscreen, Continuation.SingleTranslucent, 4097, 1),
        BelowFillRun(Primitive.Mixed, Coverage.Offscreen, Continuation.Translucent, 262143, 1),
        AtFillRun(Primitive.Mixed, Coverage.Offscreen, Continuation.Translucent, 262144, 1),
        AboveFillRun(Primitive.Mixed, Coverage.Offscreen, Continuation.Translucent, 262145, 1),
        BelowSingleLookup(Primitive.Sampled, Coverage.Offscreen, Continuation.SingleTranslucent, 1048575, 1),
        AtSingleLookup(Primitive.Sampled, Coverage.Offscreen, Continuation.SingleTranslucent, 1048576, 1),
        AboveSingleLookup(Primitive.Sampled, Coverage.Offscreen, Continuation.SingleTranslucent, 1048577, 1),
    }

    /**
     * Owns untimed exact matrix admission and independent scalar acceptance for the frozen inputs.
     */
    public companion object {
        /**
         * Requires all 72 generated AverageTime ownership/case combinations and verifies each fixed command list.
         */
        @JvmStatic
        public fun verifyWork() {
            check(Scenario.entries.size == 36)
            check(JmhWorkloadInventory.capture(listOf(NonpaintingRasterBenchmark::class.java), setOf("avgt")).size == 72)
            for (scenario in Scenario.entries) Pixels().also { it.scenario = scenario }.setup()
        }
    }
}
