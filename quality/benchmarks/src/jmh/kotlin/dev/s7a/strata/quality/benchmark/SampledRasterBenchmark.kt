package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
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
 * Separate portable-layer sampling corpus with immutable inputs prepared outside timed operations.
 * Physical extent stays constant across density, so density changes do not multiply the output area.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class SampledRasterBenchmark {
    /**
     * Rasterizes a fractional command into newly owned output storage.
     */
    @Benchmark
    public fun composition(state: Pixels): HeadlessImage = state.paint()

    /**
     * Owns one immutable image and command list on each JMH worker.
     */
    @State(Scope.Thread)
    public open class Pixels {
        /**
         * Physical raster width; height follows a 16:9 aspect ratio.
         */
        @JvmField
        @Param("320", "1920")
        public var width: Int = 320

        /**
         * Final logical-to-physical density used by portable rendering.
         */
        @JvmField
        @Param("1", "4")
        public var density: Int = 1

        /**
         * Solid opaque, solid translucent, and nonuniform sampled source inputs.
         */
        @JvmField
        @Param("Opaque", "Translucent", "Pattern")
        public var source: Source = Source.Opaque

        private lateinit var size: IntSize
        private lateinit var commands: List<DrawCommand>

        /**
         * Builds the final-density fixture without resource decoding or PNG persistence in the sample.
         */
        @Setup(Level.Trial)
        public fun setup() {
            size = IntSize(width / density, width * 9 / 16 / density)
            val imageSize = if (source == Source.Pattern) IntSize(64, 64) else IntSize(1, 1)
            val image =
                createDrawImage(
                    imageSize,
                    IntArray(imageSize.width * imageSize.height) { index ->
                        when (source) {
                            Source.Opaque -> 0xFF7195B3.toInt()
                            Source.Translucent -> 0x807195B3.toInt()
                            Source.Pattern -> 0xFF000000.toInt() or ((index * 73471) and 0xFFFFFF)
                        }
                    },
                )
            commands =
                listOf(
                    DrawCommand.SampledImage(
                        image,
                        FloatRect(0f, 0f, imageSize.width.toFloat(), imageSize.height.toFloat()),
                        FloatRect(0.125f, 0.125f, size.width - 0.125f, size.height - 0.125f),
                        ArgbColor(0xFFBFD7EF.toInt()),
                    ),
                )
        }

        /**
         * Returns a fresh raster; no presentation cache survives an operation.
         */
        public fun paint(): HeadlessImage = rasterizeHeadless(commands, size, density)
    }

    /**
     * Source varieties with different mapping and composition costs.
     */
    public enum class Source {
        /**
         * A whole one-texel opaque image with non-identity opaque RGB tint.
         */
        Opaque,

        /**
         * A whole one-texel translucent image with continuous tint multiplication.
         */
        Translucent,

        /**
         * A larger image whose two axes require fractional nearest sampling.
         */
        Pattern,
    }
}
