package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
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
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.Adler32
import java.util.zip.CRC32
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
     * Owns untimed byte parity and generated-matrix checks for this supplemental corpus.
     */
    public companion object {
        /**
         * Verifies all twelve shape/alpha cases and independent output ownership before collection.
         */
        @JvmStatic
        public fun verifyWork() {
            val expected =
                buildSet {
                    for (shape in Shape.entries) {
                        for (alpha in Alpha.entries) {
                            add(
                                JmhPerformanceRunner.workloadIdentity(
                                    "${PngEncodingBenchmark::class.java.name}.encodePng",
                                    "avgt",
                                    mapOf("shape" to shape.name, "alpha" to alpha.name),
                                ),
                            )
                            val state = Pixels()
                            state.shape = shape
                            state.alpha = alpha
                            state.setup()
                            val pixels = state.image.copyArgb()
                            val canonical = canonicalPng(shape.size, pixels)
                            val encoded = PngEncodingBenchmark().encodePng(state)
                            check(encoded.contentEquals(canonical))
                            encoded.fill(0)
                            check(state.image.encodePng().contentEquals(canonical))
                            check(state.image.copyArgb().contentEquals(pixels))
                        }
                    }
                }
            check(expected.size == 12)
            check(JmhWorkloadInventory.capture(listOf(PngEncodingBenchmark::class.java), setOf("avgt")) == expected)
        }

        private fun canonicalPng(
            size: IntSize,
            pixels: IntArray,
        ): ByteArray {
            val scanlines = ByteArrayOutputStream()
            DataOutputStream(scanlines).use { rows ->
                pixels.forEachIndexed { index, argb ->
                    if (index % size.width == 0) rows.writeByte(0)
                    rows.writeByte(argb ushr 16)
                    rows.writeByte(argb ushr 8)
                    rows.writeByte(argb)
                    rows.writeByte(argb ushr 24)
                }
            }
            val raw = scanlines.toByteArray()
            val compressed = ByteArrayOutputStream()
            DataOutputStream(compressed).use { zlib ->
                zlib.writeShort(0x7801)
                var offset = 0
                while (offset < raw.size) {
                    val count = minOf(65_535, raw.size - offset)
                    zlib.writeByte(if (offset + count == raw.size) 1 else 0)
                    zlib.writeByte(count)
                    zlib.writeByte(count ushr 8)
                    zlib.writeByte(count.inv())
                    zlib.writeByte(count.inv() ushr 8)
                    zlib.write(raw, offset, count)
                    offset += count
                }
                zlib.writeInt(Adler32().apply { update(raw) }.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).use { ihdr ->
                ihdr.writeInt(size.width)
                ihdr.writeInt(size.height)
                ihdr.write(byteArrayOf(8, 6, 0, 0, 0))
            }
            val png = ByteArrayOutputStream()
            DataOutputStream(png).use { output ->
                output.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                listOf("IHDR" to header.toByteArray(), "IDAT" to compressed.toByteArray(), "IEND" to ByteArray(0)).forEach { (type, payload) ->
                    val bytes = type.encodeToByteArray()
                    output.writeInt(payload.size)
                    output.write(bytes)
                    output.write(payload)
                    output.writeInt(
                        CRC32()
                            .apply {
                                update(bytes)
                                update(payload)
                            }.value
                            .toInt(),
                    )
                }
            }
            return png.toByteArray()
        }
    }

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
