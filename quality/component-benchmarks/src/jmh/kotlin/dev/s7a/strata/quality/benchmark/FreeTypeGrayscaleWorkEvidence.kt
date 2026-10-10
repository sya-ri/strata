package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontLoadLimitException
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.nio.ByteBuffer
import java.nio.file.Files

/**
 * Independent complete converter/glyph/Text checks and actual observed native inputs outside every sample.
 * This verifier is necessary corpus admission, separate from the thirty-control whole-Issue acceptance matrix.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object FreeTypeGrayscaleWorkEvidence {
    /**
     * Validates the complete generated inventory and every result without a timing threshold.
     * The emitted receipt is an untimed observation; it is never a frozen measured-comparison receipt.
     */
    public fun verify() {
        check(JmhWorkloadInventory.capture(listOf(FreeTypeGrayscaleBenchmark::class.java), setOf("avgt")).size == 40)
        val records = JsonArray()
        converters(records)
        glyphs(records)
        controls(records)
        check(records.size() == 40)
        frozenAdmission(records)
        println(
            JsonObject().apply {
                addProperty("contract", "freetype-grayscale-corpus-admission-v1")
                addProperty("status", "passed")
                addProperty("measured", false)
                addProperty("fixture_count", 36)
                addProperty("row_count", records.size())
                addProperty("font_sha256", FontRasterAssets.digest(FreeTypeGrayscaleAssets.font()))
                add("rows", records)
            },
        )
    }

    private fun frozenAdmission(records: JsonArray) {
        val path = JmhFixtureSelection.inputs()["freetype-grayscale-admission"] ?: return
        val reference = JsonParser.parseString(Files.readString(path)).asJsonObject
        check(reference.get("fixture_count").asInt == 36 && reference.get("row_count").asInt == 40)
        check(reference.get("font_sha256").asString == FontRasterAssets.digest(FreeTypeGrayscaleAssets.font()))
        check(reference.getAsJsonArray("rows") == records) { "Actual native inputs or complete outputs changed after admission freeze" }
    }

    private fun converters(records: JsonArray) {
        listOf(1, 8, 16, 64, 256).forEach { axis ->
            FreeTypeGrayscaleBenchmark.Layout.entries.forEach { layout ->
                val owner = FreeTypeBitmapFixture(axis, layout)
                val (image, expected) =
                    owner.use {
                        val expected = it.expected()
                        val result = it.image()
                        check(result.copyArgb().contentEquals(expected)) { "Actual converter differs from complete scalar-address oracle" }
                        it.overwrite()
                        check(result.copyArgb().contentEquals(expected))
                        result to expected
                    }
                check(image.copyArgb().contentEquals(expected)) { "Converted image retained native storage" }
                image.copyArgb().fill(0)
                check(image.copyArgb().contentEquals(expected)) { "Pixel ownership escaped through extraction" }
                records.add(record("converter:axis=$axis,layout=$layout", expected).apply {
                    addProperty("width", axis)
                    addProperty("height", axis)
                    addProperty("pitch", (axis + if (layout.padded) 3 else 0) * if (layout.reversed) -1 else 1)
                })
            }
        }
    }

    private fun glyphs(records: JsonArray) {
        FreeTypeGlyphFixture.entries.forEach { fixture ->
            val reference = FreeTypeScalarOracle.glyph(fixture)
            FreeTypeGlyphOwner(fixture).use { owner ->
                val actual = owner.glyph()
                equalGlyph(reference.glyph, actual)
                val expected = owner.referenceText(reference.glyph)
                val frame = owner.dirtyText()
                equalFrame(expected, frame)
                records.add(glyphRecord("completeFreeTypeGlyph:fixture=$fixture", actual, reference.pitch))
                records.add(record("completeDirtyTextFrame:fixture=$fixture", rasterizeHeadless(frame.drawCommands, FreeTypeGlyphOwner.viewport).copyArgb()).apply {
                    addProperty("width", frame.size.width)
                    addProperty("height", frame.size.height)
                    addProperty("draw_commands", frame.drawCommands.size)
                    addProperty("semantics", frame.semantics.size)
                })
            }
        }
    }

    private fun controls(records: JsonArray) {
        FreeTypeGrayscaleBenchmark.Control.entries.forEach { control ->
            val owner = FreeTypeControlFixture(control)
            val result = owner.use { it.sample() }
            val record = JsonObject().apply { addProperty("id", "control:operation=$control") }
            when (control) {
                FreeTypeGrayscaleBenchmark.Control.MissingFreeTypeGlyph -> check(result == true)
                FreeTypeGrayscaleBenchmark.Control.EmptyFreeTypeGlyph -> check((result as MinecraftFontGlyph).image == null)
                FreeTypeGrayscaleBenchmark.Control.AtlasRejectedGlyph -> check((result as MinecraftFontGlyph).image == null && result.oversizedRasterSize != null)
                FreeTypeGrayscaleBenchmark.Control.ImageLimitRejectedGlyph -> check(result is MinecraftFontLoadLimitException)
                FreeTypeGrayscaleBenchmark.Control.SnapshotLoad -> check((result as MinecraftFontSnapshot).diagnostics.isEmpty())
                FreeTypeGrayscaleBenchmark.Control.MalformedConverterInput -> {
                    val failures = result as List<*>
                    check(failures.size == 3 && failures.all { it is IllegalArgumentException })
                    val expected = listOf("The ttf provider requires grayscale glyphs.", "FreeType glyph dimensions changed during rasterization.", "FreeType returned an invalid glyph stride.")
                    check(failures.map { (it as Throwable).message } == expected)
                }
                FreeTypeGrayscaleBenchmark.Control.CompleteCleanTextFrame -> {
                    val frame = result as RuntimeUiFrame
                    FreeTypeGlyphOwner(FreeTypeGlyphFixture.SmallGlyphOne).use { reference ->
                        equalFrame(reference.referenceText(FreeTypeScalarOracle.glyph(FreeTypeGlyphFixture.SmallGlyphOne).glyph), frame)
                    }
                }

                else -> {
                    val glyph = result as MinecraftFontGlyph
                    checkNotNull(glyph.image)
                    record.addProperty("pixel_sha256", pixelDigest(checkNotNull(glyph.image).copyArgb()))
                }
            }
            records.add(record)
        }
    }

    private fun equalGlyph(
        reference: MinecraftFontGlyph,
        actual: MinecraftFontGlyph,
    ) {
        check(metricBits(reference) == metricBits(actual)) { "Exact native Float metric bits changed" }
        check(reference.channel == actual.channel && reference.orientation == actual.orientation && reference.oversizedRasterSize == actual.oversizedRasterSize)
        check(reference.image?.size == actual.image?.size)
        check(checkNotNull(reference.image).copyArgb().contentEquals(checkNotNull(actual.image).copyArgb())) { "Complete real glyph differs from independent native scalar oracle" }
    }

    private fun equalFrame(
        reference: RuntimeUiFrame,
        actual: RuntimeUiFrame,
    ) {
        check(reference.size == actual.size && reference.drawCommands == actual.drawCommands && reference.semantics == actual.semantics) { "Complete Text geometry, command order or semantics changed" }
        check(rasterizeHeadless(reference.drawCommands, FreeTypeGlyphOwner.viewport).copyArgb().contentEquals(rasterizeHeadless(actual.drawCommands, FreeTypeGlyphOwner.viewport).copyArgb())) { "Complete Text pixels changed" }
    }

    private fun metricBits(glyph: MinecraftFontGlyph): List<Int> = listOf(glyph.advance, glyph.left, glyph.top, glyph.right, glyph.bottom, glyph.boldOffset, glyph.shadowOffset).map(Float::toRawBits)

    private fun glyphRecord(
        id: String,
        glyph: MinecraftFontGlyph,
        pitch: Int,
    ): JsonObject =
        record(id, checkNotNull(glyph.image).copyArgb()).apply {
            addProperty("width", checkNotNull(glyph.image).size.width)
            addProperty("height", checkNotNull(glyph.image).size.height)
            addProperty("pitch", pitch)
            add("metric_bits", JsonArray().apply { metricBits(glyph).forEach(::add) })
        }

    private fun record(
        id: String,
        pixels: IntArray,
    ): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("argb_sha256", pixelDigest(pixels))
    }

    private fun pixelDigest(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(Math.multiplyExact(pixels.size, 4))
        pixels.forEach(bytes::putInt)
        return FontRasterAssets.digest(bytes.array())
    }
}
