package dev.s7a.strata.quality.benchmark

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
 * Separate mixed-pixel raster workload; it does not alter the historical retained overlay matrix.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class NonuniformOverlayBenchmark {
    /**
     * Switches between two prepared nonuniform source images and composes every ordered translucent fill.
     */
    @Benchmark
    public fun composition(state: MixedPixels): HeadlessImage = state.next()

    /**
     * Owns immutable gradient inputs on one JMH worker; preparation is outside measured operations.
     */
    @State(Scope.Thread)
    public open class MixedPixels {
        /**
         * Uses the historical overlay corpus's two physical 16:9 extents without modifying its fixtures.
         */
        @JvmField
        @Param("320", "1920")
        public var width: Int = 320

        /**
         * Ordered full-area alpha stack measured separately from initial source preparation.
         */
        @JvmField
        @Param("1", "16", "64")
        public var layers: Int = 1

        private lateinit var size: IntSize
        private lateinit var frames: List<List<DrawCommand>>
        private var phase = 0

        /**
         * Prepares different RGB channels and mixed destination alpha at every logical pixel.
         */
        @Setup(Level.Trial)
        public fun setup() {
            size = IntSize(width, width * 9 / 16)
            val bounds = IntRect(0, 0, size.width, size.height)
            frames =
                List(2) { variant ->
                    val image =
                        createDrawImage(
                            size,
                            IntArray(size.width * size.height) { index ->
                                val value = index + variant
                                ((value and 255) shl 24) or (((value * 17) and 255) shl 16) or (((value * 31) and 255) shl 8) or ((value * 47) and 255)
                            },
                        )
                    listOf(DrawCommand.BlitImage(image, bounds, bounds)) +
                        List(layers) { index ->
                            DrawCommand.FillRectangle(bounds, ArgbColor((listOf(1, 16, 128, 254)[index % 4] shl 24) or ((index * 73471) and 0xFFFFFF)))
                        }
                }
        }

        /**
         * Retains only prepared inputs; each operation returns a fresh output owned by JMH.
         */
        public fun next(): HeadlessImage {
            phase = 1 - phase
            return rasterizeHeadless(frames[phase], size)
        }
    }
}
