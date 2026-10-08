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
 * Separates destination palette reuse from source weights using a fixed one-to-one sampled output.
 * Immutable inputs are prepared outside measurement; every operation owns fresh output and source-weight scratch.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class DestinationPaletteBenchmark {
    /**
     * Includes ordered background blitting and translucent sampling without retaining the measured image.
     */
    @Benchmark
    public fun composition(state: Pixels): HeadlessImage = state.paint()

    /**
     * Owns fixed inputs for one JMH worker; all 8192 output pixels remain independently sampled.
     */
    @State(Scope.Thread)
    public open class Pixels {
        /**
         * Number of source alpha bytes before the repeating sequence restarts; the one-alpha control uses byte 128.
         */
        @JvmField
        @Param("1", "16", "256")
        public var alphas: Int = 1

        /**
         * Full destination ARGB pattern observed by the sampled command.
         */
        @JvmField
        @Param("Transparent", "Translucent", "Opaque", "Heterogeneous", "EarlyChange")
        public var background: Background = Background.Opaque

        private val size = IntSize(128, 64)
        private lateinit var commands: List<DrawCommand>

        /**
         * Builds the source and background without decoding resources or creating measured output.
         * The first 16 uniform destinations in EarlyChange expose allocation discarded before reuse is established.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val source =
                createDrawImage(
                    size,
                    IntArray(size.width * size.height) { index ->
                        val alpha = if (alphas == 1) 128 else index % alphas
                        (alpha shl 24) or (index * 73471 and 0xFFFFFF)
                    },
                )
            val destination =
                createDrawImage(
                    size,
                    IntArray(size.width * size.height) { index ->
                        when (background) {
                            Background.Transparent -> 0
                            Background.Translucent -> 0x804A6789.toInt()
                            Background.Opaque -> 0xFF234567.toInt()
                            Background.Heterogeneous -> ((128 + index % 128) shl 24) or (index * 37199 and 0xFFFFFF)
                            Background.EarlyChange -> if (index < 16) 0xFF234567.toInt() else 0xFF4A6789.toInt()
                        }
                    },
                )
            val bounds = IntRect(0, 0, size.width, size.height)
            val rectangle = FloatRect(0f, 0f, size.width.toFloat(), size.height.toFloat())
            commands =
                listOf(
                    DrawCommand.BlitImagePixels(destination, bounds, bounds),
                    DrawCommand.SampledImage(source, rectangle, rectangle, ArgbColor(0x80A4C6E8.toInt()), 0f),
                )
        }

        /**
         * Returns a complete fresh raster, preserving the same current-call ownership on both revisions.
         */
        public fun paint(): HeadlessImage = rasterizeHeadless(commands, size)
    }

    /**
     * Uniform controls and early heterogeneity exercise useful and wasted palette admissions separately.
     */
    public enum class Background {
        /**
         * Transparent black destination with no source-over background contribution.
         */
        Transparent,

        /**
         * Identical alpha-128 destination at every pixel.
         */
        Translucent,

        /**
         * Identical opaque destination at every pixel.
         */
        Opaque,

        /**
         * Destination alpha and color vary at each physical pixel.
         */
        Heterogeneous,

        /**
         * The first 16 destinations are identical before one permanent ARGB change.
         */
        EarlyChange,
    }
}
