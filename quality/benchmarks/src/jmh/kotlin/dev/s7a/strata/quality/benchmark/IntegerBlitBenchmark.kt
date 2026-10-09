package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.headless.HeadlessImage
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Fixed integer-blit corpus with resident immutable inputs and fresh complete rasters per operation.
 * Normal densities 1–4 remain separate from the additional headless-only density-256 overflow controls.
 * Source generation, reference painting and work verification happen outside collection.
 */
public open class IntegerBlitBenchmark {
    /**
     * Executes every ordered command and allocation in one complete isolated raster operation.
     */
    @Benchmark
    public fun raster(state: Pixels): HeadlessImage = state.paint()

    /**
     * Owns one frozen scene on each worker; no output or coordinate map survives an operation.
     */
    @State(Scope.Thread)
    public open class Pixels {
        /**
         * Original logical-cell replication or independent physical-pixel sampling.
         */
        @JvmField
        @Param("Logical", "Physical")
        public var path: Path = Path.Logical

        /**
         * Complete affected and scalar-control matrix, including the cold overflow branch.
         */
        @JvmField
        @Param(
            "Upscale1",
            "Upscale2",
            "Upscale3",
            "Upscale4",
            "Downscale1",
            "Downscale2",
            "Downscale3",
            "Downscale4",
            "Small1",
            "Small2",
            "Small3",
            "Small4",
            "OneRow1",
            "OneRow2",
            "OneRow3",
            "OneRow4",
            "Identity1",
            "Identity2",
            "Identity3",
            "Identity4",
            "OneTexel1",
            "OneTexel2",
            "OneTexel3",
            "OneTexel4",
            "OpaqueUniform",
            "OpaqueHeterogeneous",
            "TranslucentUniform",
            "TranslucentHeterogeneous",
            "TransparentUniform",
            "TransparentHeterogeneous",
            "TallNarrow",
            "FewRows",
            "ThresholdBelow",
            "ThresholdAt",
            "WidthLimit",
            "WidthFallback",
            "CroppedClip",
            "NonzeroOrigin",
            "ExtremeLong",
            "BigInteger",
            "BigIntegerOneRow",
            "EmptyClip",
        )
        public var scenario: Scenario = Scenario.Small1

        private lateinit var scene: IntegerBlitScene

        /**
         * Generates one real immutable source and verifies all final pixels with the independent reference.
         */
        @Setup(Level.Trial)
        public fun setup() {
            scene = IntegerBlitScene.create(path, scenario)
            scene.verify()
        }

        /**
         * Returns newly owned raster storage without PNG encoding or presentation work.
         */
        public fun paint(): HeadlessImage = scene.paint()
    }

    /**
     * Public command contracts, with unchanged integer nearest-center equations.
     */
    public enum class Path {
        Logical,
        Physical,
    }

    /**
     * Fixed scene dimensions and density; exceptional crops and clips are owned by the scene factory.
     * Upscale, downscale, small, one-row, identity and one-texel cases each retain every normal density.
     * BigInteger cases use the lawful complete-raster control from Issue 190, with resident 32-MiB sources.
     * @property width logical raster width.
     * @property height logical raster height.
     * @property sourceWidth immutable source width.
     * @property sourceHeight immutable source height.
     * @property density positive headless output density, including separately labeled overflow controls.
     */
    public enum class Scenario(
        public val width: Int,
        public val height: Int,
        public val sourceWidth: Int,
        public val sourceHeight: Int,
        public val density: Int,
    ) {
        Upscale1(512, 512, 256, 256, 1),

        Upscale2(512, 512, 256, 256, 2),

        Upscale3(512, 512, 256, 256, 3),

        Upscale4(512, 512, 256, 256, 4),

        Downscale1(97, 65, 176, 128, 1),

        Downscale2(97, 65, 176, 128, 2),

        Downscale3(97, 65, 176, 128, 3),

        Downscale4(97, 65, 176, 128, 4),

        Small1(8, 7, 13, 11, 1),

        Small2(8, 7, 13, 11, 2),

        Small3(8, 7, 13, 11, 3),

        Small4(8, 7, 13, 11, 4),

        OneRow1(1024, 1, 127, 3, 1),

        OneRow2(1024, 1, 127, 3, 2),

        OneRow3(1024, 1, 127, 3, 3),

        OneRow4(1024, 1, 127, 3, 4),

        Identity1(96, 64, 96, 64, 1),

        Identity2(96, 64, 96, 64, 2),

        Identity3(96, 64, 96, 64, 3),

        Identity4(96, 64, 96, 64, 4),

        OneTexel1(96, 64, 1, 1, 1),

        OneTexel2(96, 64, 1, 1, 2),

        OneTexel3(96, 64, 1, 1, 3),

        OneTexel4(96, 64, 1, 1, 4),

        OpaqueUniform(96, 64, 67, 43, 2),

        OpaqueHeterogeneous(96, 64, 67, 43, 2),

        TranslucentUniform(96, 64, 67, 43, 2),

        TranslucentHeterogeneous(96, 64, 67, 43, 2),

        TransparentUniform(96, 64, 67, 43, 2),

        TransparentHeterogeneous(96, 64, 67, 43, 2),

        TallNarrow(8, 512, 33, 127, 2),

        FewRows(2048, 3, 123, 17, 1),

        ThresholdBelow(63, 65, 91, 77, 1),

        ThresholdAt(64, 64, 91, 77, 1),

        WidthLimit(16384, 4, 123, 17, 1),

        WidthFallback(16385, 4, 123, 17, 1),

        CroppedClip(96, 80, 73, 53, 3),

        NonzeroOrigin(64, 64, 73, 53, 4),

        ExtremeLong(64, 64, 33, 17, 4),

        BigInteger(1, 1, 8388609, 1, 256),

        BigIntegerOneRow(1, 1, 8388609, 1, 256),

        EmptyClip(96, 64, 67, 43, 2),
    }

    /**
     * Registers this independent corpus without modifying the historical 54-case defaults.
     */
    public companion object {
        /**
         * Requires all 84 generated cases and complete independent pixels outside timing.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(IntegerBlitBenchmark::class.java), setOf("avgt")).size == 84)
            for (path in Path.entries) {
                for (scenario in Scenario.entries) {
                    IntegerBlitScene.create(path, scenario).verify()
                }
            }
        }
    }
}
