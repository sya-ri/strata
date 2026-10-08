package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
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
 * Supplements the unchanged cold-image corpus with rectangular and stored-block boundary PNG encoding.
 * Source construction, decoding, and verification stay outside the timed fresh-output operation.
 */
@OptIn(InternalStrataRuntimeApi::class)
public open class PngEncodingBenchmark {
    /**
     * Encodes prepared immutable pixels into an independent canonical PNG without filesystem I/O.
     */
    @Benchmark
    public fun encodePng(state: Pixels): ByteArray = state.image.encodePng()

    /**
     * Owns one prepared immutable image per worker, without retaining encoded output history.
     */
    @State(Scope.Thread)
    public open class Pixels {
        /**
         * Physical dimensions selected independently of pixel alpha.
         */
        @JvmField
        @Param("FullHd", "BlockBelow", "BlockExact", "BlockAbove")
        public var shape: Shape = Shape.FullHd

        /**
         * Alpha distribution; RGB remains nonuniform where visible.
         */
        @JvmField
        @Param("Transparent", "Opaque", "PartialAlpha")
        public var alpha: Alpha = Alpha.PartialAlpha

        /**
         * Immutable source raster constructed before any timed encoding.
         */
        public lateinit var image: HeadlessImage
            private set

        /**
         * Validates decoded pixels and fresh output identity before timing.
         */
        @Setup(Level.Trial)
        public fun setup() {
            val size = shape.size
            val pixels =
                IntArray(size.width * size.height) { index ->
                    val opacity =
                        when (alpha) {
                            Alpha.Transparent -> 0
                            Alpha.Opaque -> 255
                            Alpha.PartialAlpha -> 1 + index % 255
                        }
                    (opacity shl 24) or (index * 73_471 and 0xFFFFFF)
                }
            val source = createDrawImage(size, pixels)
            val bounds = IntRect(0, 0, size.width, size.height)
            image = rasterizeHeadless(listOf(DrawCommand.BlitImage(source, bounds, bounds)), size)
            val encoded = image.encodePng()
            val repeated = image.encodePng()
            check(encoded !== repeated && encoded.contentEquals(repeated))
            ByteArrayInputStream(encoded).use { input ->
                val decoded = checkNotNull(ImageIO.read(input))
                check(decoded.getRGB(0, 0, size.width, size.height, null, 0, size.width).contentEquals(image.copyArgb()))
            }
        }
    }

    /**
     * Legal filter-zero RGBA scanline lengths surrounding the first maximum stored block.
     */
    public enum class Shape(
        public val size: IntSize,
    ) {
        /**
         * A rectangular full-HD export.
         */
        FullHd(IntSize(1920, 1080)),

        /**
         * 65,534 scanline bytes, immediately below the first block boundary.
         */
        BlockBelow(IntSize(54, 302)),

        /**
         * Exactly 65,535 scanline bytes.
         */
        BlockExact(IntSize(1, 13_107)),

        /**
         * 65,537 scanline bytes; positive RGBA images cannot contain exactly 65,536.
         */
        BlockAbove(IntSize(16_384, 1)),
    }

    /**
     * Transparent, opaque, and varying nonzero source alpha inputs.
     */
    public enum class Alpha {
        Transparent,
        Opaque,
        PartialAlpha,
    }
}
