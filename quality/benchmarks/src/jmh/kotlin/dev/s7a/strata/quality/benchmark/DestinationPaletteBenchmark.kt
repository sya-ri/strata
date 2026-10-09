package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
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
import kotlin.math.roundToInt

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

        /**
         * Requires fixed command work and independent scalar pixels outside JMH setup and timing.
         * Consecutive paints must return separate immutable complete images without changing the source inputs.
         */
        public fun verifyWork() {
            check(size == IntSize(128, 64) && commands.size == 2)
            val backdrop = commands[0]
            val sampled = commands[1]
            check(backdrop is DrawCommand.BlitImagePixels && sampled is DrawCommand.SampledImage)
            val bounds = IntRect(0, 0, size.width, size.height)
            val rectangle = FloatRect(0f, 0f, size.width.toFloat(), size.height.toFloat())
            check(backdrop.source == bounds && backdrop.destination == bounds && backdrop.image.size == size)
            check(sampled.source == rectangle && sampled.destination == rectangle && sampled.image.size == size)
            check(sampled.tint.value == 0x80A4C6E8.toInt() && sampled.alphaCutoff == 0f)
            check(sampled.orientation.flipX.not() && sampled.orientation.flipY.not())
            val expected =
                IntArray(8192) { index ->
                    val alpha = if (alphas == 1) 128 else index % alphas
                    val source = (alpha shl 24) or (index * 73471 and 0xFFFFFF)
                    val destination = referenceDestination(index)
                    check(sampled.image.argbAt(index % size.width, index / size.width) == source)
                    check(backdrop.image.argbAt(index % size.width, index / size.width) == destination)
                    reference(source, destination)
                }
            val sourceInput = sampled.image.copyArgb()
            val backgroundInput = backdrop.image.copyArgb()
            val first = paint()
            check(first.size == size && first.copyArgb().contentEquals(expected)) { "Independent sampled reference: $alphas/$background" }
            val second = paint()
            check((first === second).not())
            check(second.copyArgb().contentEquals(expected))
            check(first.copyArgb().contentEquals(expected))
            check(sampled.image.copyArgb().contentEquals(sourceInput))
            check(backdrop.image.copyArgb().contentEquals(backgroundInput))
        }

        private fun referenceDestination(index: Int): Int =
            when (background) {
                Background.Transparent -> 0
                Background.Translucent -> 0x804A6789.toInt()
                Background.Opaque -> 0xFF234567.toInt()
                Background.Heterogeneous -> ((128 + index % 128) shl 24) or (index * 37199 and 0xFFFFFF)
                Background.EarlyChange -> if (index < 16) 0xFF234567.toInt() else 0xFF4A6789.toInt()
            }

        // Scalar reference deliberately has no palette, source-weight scratch, row/span reuse or pair shortcut.
        private fun reference(
            source: Int,
            destination: Int,
        ): Int {
            val tint = 0x80A4C6E8.toInt()
            val alpha = channel(source, 24) * channel(tint, 24)
            if (alpha == 0f) return destination
            val weight = channel(destination, 24) * (1f - alpha)
            val outputAlpha = alpha + weight
            val alphaByte = (outputAlpha * 255f).roundToInt().coerceIn(0, 255)
            if (alphaByte == 0) return 0
            var result = alphaByte shl 24
            for (shift in listOf(16, 8, 0)) {
                val value = (channel(source, shift) * channel(tint, shift) * alpha + channel(destination, shift) * weight) / outputAlpha
                result = result or ((value * 255f).roundToInt().coerceIn(0, 255) shl shift)
            }
            return result
        }

        private fun channel(
            color: Int,
            shift: Int,
        ): Float = (color ushr shift and 255).toFloat() / 255f
    }

    /**
     * Fixture-owned deterministic gates for the complete independent destination-palette corpus.
     */
    public companion object {
        /**
         * Requires all fifteen compiled parameter cases and verifies their actual input work and exact pixels.
         * Shared registry discovery invokes this hook before collecting any timing evidence.
         */
        @JvmStatic
        public fun verifyWork() {
            check(JmhWorkloadInventory.capture(listOf(DestinationPaletteBenchmark::class.java), setOf("avgt")).size == 15)
            for (alphas in listOf(1, 16, 256)) {
                for (background in Background.entries) {
                    val state = Pixels()
                    state.alphas = alphas
                    state.background = background
                    state.setup()
                    state.verifyWork()
                }
            }
        }
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
