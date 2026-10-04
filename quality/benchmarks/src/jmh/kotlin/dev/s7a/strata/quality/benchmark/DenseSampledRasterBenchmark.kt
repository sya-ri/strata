package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.HeadlessImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * Separate full-HD corpus that varies source detail independently of physical output area.
 * Source construction is outside measurement; opaque and translucent images use the same patterned RGB values.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class DenseSampledRasterBenchmark {
    /**
     * Composes a tinted, fractional nearest-sampled image over an opaque destination in newly owned storage.
     */
    @Benchmark
    public fun composition(state: Pixels): HeadlessImage = state.paint()

    /**
     * Owns immutable deterministic inputs for one JMH worker and retains no measured output.
     */
    @State(Scope.Thread)
    public open class Pixels {
        /**
         * Source width and height in texels; the physical output remains 1920 by 1080.
         */
        @JvmField
        @Param("64", "256", "1024")
        public var resolution: Int = 64

        /**
         * Source alpha used with identical spatially varying colors and opaque RGB tint.
         */
        @JvmField
        @Param("Opaque", "Translucent")
        public var source: Source = Source.Opaque

        private val size = IntSize(1920, 1080)
        private lateinit var commands: List<DrawCommand>

        /**
         * Prepares source pixels and ordered commands without decoding resources or saving images in the sample.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val image =
                createDrawImage(
                    IntSize(resolution, resolution),
                    IntArray(resolution * resolution) { index ->
                        val alpha = if (source == Source.Opaque) 255 else 128
                        (alpha shl 24) or ((index * 73471) and 0xFFFFFF)
                    },
                )
            commands =
                listOf(
                    DrawCommand.FillRectangle(IntRect(0, 0, size.width, size.height), ArgbColor(0xFF234567.toInt())),
                    DrawCommand.SampledImage(
                        image,
                        FloatRect(0f, 0f, resolution.toFloat(), resolution.toFloat()),
                        FloatRect(0.125f, 0.125f, size.width - 0.125f, size.height - 0.125f),
                        ArgbColor(0xFFBFD7EF.toInt()),
                    ),
                )
        }

        /**
         * Produces a complete fresh raster with no cross-operation image or color reuse.
         */
        public fun paint(): HeadlessImage = rasterizeHeadless(commands, size)
    }

    /**
     * Alpha varieties that distinguish overwrite from source-over composition cost.
     */
    public enum class Source {
        /**
         * Fully opaque patterned texels.
         */
        Opaque,

        /**
         * Patterned texels with alpha 128 composed over an opaque destination.
         */
        Translucent,
    }
}
