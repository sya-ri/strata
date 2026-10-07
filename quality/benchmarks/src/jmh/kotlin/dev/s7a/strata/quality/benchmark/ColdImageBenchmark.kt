package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
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
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Independent cold-decoder and deterministic PNG-encoding workloads, excluded from steady presentation.
 * Encoded input is resident before timing; this corpus makes no filesystem-cache or network-cold claim.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class ColdImageBenchmark {
    /**
     * Opens a fresh stream, decodes a PNG, and detaches immutable straight-ARGB pixels on each operation.
     */
    @Benchmark
    public fun acquireAndDecode(state: Pixels): DrawImage = state.decode()

    /**
     * Encodes a prepared immutable raster into fresh PNG storage; it does not write a file.
     */
    @Benchmark
    public fun encodePng(state: Pixels): ByteArray = state.image.encodePng()

    /**
     * Owns identical nonuniform source pixels and encoded bytes for one worker.
     */
    @State(Scope.Thread)
    public open class Pixels {
        /**
         * Square source extent in pixels.
         */
        @JvmField
        @Param("64", "256", "1024")
        public var resolution: Int = 64

        /**
         * Immutable raster prepared outside both independent operation boundaries.
         */
        public lateinit var image: HeadlessImage
            private set
        private lateinit var encoded: ByteArray

        /**
         * Builds patterned RGBA inputs and verifies the decoded straight-ARGB value before sampling.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val size = IntSize(resolution, resolution)
            val source = createDrawImage(size, IntArray(resolution * resolution) { index -> ((1 + index % 255) shl 24) or (index * 73471 and 0xFFFFFF) })
            val bounds = IntRect(0, 0, resolution, resolution)
            image = rasterizeHeadless(listOf(DrawCommand.BlitImage(source, bounds, bounds)), size)
            encoded = image.encodePng()
            check(decode().copyArgb().contentEquals(image.copyArgb()))
        }

        /**
         * Acquires a new decoder and exclusively owned pixels; no decoded value survives the operation.
         */
        public fun decode(): DrawImage =
            ByteArrayInputStream(encoded).use { input ->
                val decoded = checkNotNull(ImageIO.read(input))
                val pixels = decoded.getRGB(0, 0, resolution, resolution, null, 0, resolution)
                createDrawImage(IntSize(resolution, resolution), pixels)
            }
    }
}
