package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Independent complete-pixel, matrix and lifetime checks performed outside all measured operations.
 */
public object FontRasterWorkEvidence {
    /**
     * With one argument prepares fresh frozen PNG inputs; with none verifies the complete corpus.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size <= 1)
        if (args.isNotEmpty()) {
            FontRasterAssets.preparePngs(Path.of(args.single()))
            return
        }
        verify()
    }

    /**
     * Checks all 22 generated cases and publishes stable complete-image/metric fingerprints for paired parity.
     */
    public fun verify() {
        check(JmhWorkloadInventory.capture(listOf(FontRasterOwnershipBenchmark::class.java), setOf("avgt")).size == 22)
        check(JmhWorkloadInventory.capture(listOf(FontProviderBenchmark::class.java, FontTextBenchmark::class.java), setOf("avgt")).size == 42)
        val records = JsonArray()
        val inputs = JsonObject()
        verifyPngs(records, inputs)
        verifyBitmapCells(records)
        verifyUnihex(records)
        verifyTrueType(records)
        verifyControls(records)
        check(records.size() == 22)
        inputs.addProperty("cc0-geometric-font", FontRasterAssets.digest(Files.readAllBytes(Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))))))
        println(
            JsonObject().apply {
                addProperty("contract", "font-raster-ownership-parity-v1")
                addProperty("status", "passed")
                addProperty("measured", false)
                addProperty("case_count", records.size())
                add("inputs", inputs)
                add("cases", records)
            },
        )
    }

    private fun verifyPngs(
        records: JsonArray,
        inputs: JsonObject,
    ) {
        listOf(32, 256, 1024).forEach { axis ->
            val owner = FontRasterOwnershipBenchmark.PngSession().apply { this.axis = axis }
            owner.setup()
            val expected = IntArray(axis * axis) { FontRasterAssets.pixel(it % axis, it / axis) }
            val image =
                try {
                    val actual = owner.decode()
                    imageEquals(actual, IntSize(axis, axis), expected)
                    imageEquals(owner.decode(), IntSize(axis, axis), expected)
                    actual.copyArgb().fill(0)
                    actual
                } finally {
                    owner.close()
                }
            imageEquals(image, IntSize(axis, axis), expected)
            records.add(imageRecord("decodePng:axis=$axis", image, axis * axis * 4L))
            inputs.addProperty("png-$axis", FontRasterAssets.digest(FontRasterAssets.png(axis)))
        }
    }

    private fun verifyBitmapCells(records: JsonArray) {
        listOf(8, 64, 256, 257).forEach { cell ->
            val owner = FontRasterOwnershipBenchmark.BitmapSession().apply { this.cell = cell }
            owner.setup()
            val initialDecodes = owner.decodes
            val glyph =
                try {
                    val actual = owner.glyph()
                    equalGlyph(actual, owner.glyph())
                    check(owner.decodes - initialDecodes == 2) { "Disabled bitmap cache skipped or duplicated source reads" }
                    check(actual.advance == (cell + 1).toFloat())
                    if (cell <= 256) imageEquals(checkNotNull(actual.image), IntSize(cell, cell), IntArray(cell * cell) { FontRasterAssets.pixel(it % cell, it / cell) })
                    actual
                } finally {
                    owner.close()
                }
            val pixels = checkNotNull(glyph.image).copyArgb()
            glyph.image?.copyArgb()?.fill(0)
            check(checkNotNull(glyph.image).copyArgb().contentEquals(pixels))
            records.add(glyphRecord("bitmapCell:cell=$cell", glyph, if (cell <= 256) cell * cell * 4L else 0L))
        }
    }

    private fun verifyUnihex(records: JsonArray) {
        listOf(8, 16, 32).forEach { width ->
            val owner = FontRasterOwnershipBenchmark.UnihexSession().apply { this.width = width }
            owner.setup()
            val expected = IntArray(width * 16) { if (it % width in setOf(0, width - 1)) -1 else 0 }
            val glyph =
                try {
                    val actual = owner.glyph()
                    equalGlyph(actual, owner.glyph())
                    check(actual.advance == width / 2f + 1f)
                    imageEquals(checkNotNull(actual.image), IntSize(width, 16), expected)
                    actual
                } finally {
                    owner.close()
                }
            imageEquals(checkNotNull(glyph.image), IntSize(width, 16), expected)
            records.add(glyphRecord("unihex:width=$width", glyph, width * 16 * 4L))
        }
    }

    private fun verifyTrueType(records: JsonArray) {
        MinecraftTrueTypeRasterizer.entries.forEach { rasterizer ->
            listOf(11, 96).forEach { size ->
                val owner =
                    FontRasterOwnershipBenchmark.TrueTypeSession().apply {
                        this.rasterizer = rasterizer
                        this.size = size
                    }
                owner.setup()
                val glyph =
                    try {
                        val actual = owner.glyph()
                        repeat(3) { equalGlyph(actual, owner.glyph()) }
                        actual
                    } finally {
                        owner.close()
                    }
                val image = checkNotNull(glyph.image)
                val pixels = image.copyArgb()
                image.copyArgb().fill(0)
                check(image.copyArgb().contentEquals(pixels))
                records.add(glyphRecord("trueType:rasterizer=$rasterizer,size=$size", glyph, image.size.width * image.size.height * 4L))
            }
        }
    }

    private fun verifyControls(records: JsonArray) {
        listOf(FontWorkload.BitmapCached, FontWorkload.UnihexCached, FontWorkload.StbCached, FontWorkload.FreeTypeCached).forEach { workload ->
            val owner = FontRasterOwnershipBenchmark.ControlSession().apply { this.workload = workload }
            owner.setup()
            val results =
                try {
                    val warm = owner.warm()
                    equalGlyph(warm, owner.warm())
                    val lifetime = owner.lifecycle()
                    equalGlyph(lifetime, owner.lifecycle())
                    equalGlyph(warm, owner.warm())
                    warm to lifetime
                } finally {
                    owner.close()
                }
            records.add(glyphRecord("warm:workload=$workload", results.first, 0L))
            records.add(glyphRecord("lifecycle:workload=$workload", results.second, null))
        }
    }

    private fun imageEquals(
        image: DrawImage,
        size: IntSize,
        pixels: IntArray,
    ) {
        check(image.size == size && image.copyArgb().contentEquals(pixels)) { "Complete raster pixels differ from the independent prepared input" }
    }

    private fun equalGlyph(
        first: MinecraftFontGlyph,
        second: MinecraftFontGlyph,
    ) {
        check(first.copy(image = null) == second.copy(image = null)) { "Glyph metrics changed during source reuse" }
        check(first.image?.size == second.image?.size && (first.image?.copyArgb()?.contentEquals(second.image?.copyArgb()) ?: (second.image == null))) { "Complete glyph pixels changed during source reuse" }
    }

    private fun glyphRecord(
        id: String,
        glyph: MinecraftFontGlyph,
        copyBytes: Long?,
    ): JsonObject =
        imageRecord(id, glyph.image, copyBytes).apply {
            add(
                "metric_bits",
                JsonArray().apply {
                    listOf(glyph.advance, glyph.left, glyph.top, glyph.right, glyph.bottom, glyph.boldOffset, glyph.shadowOffset).forEach { add(it.toRawBits()) }
                },
            )
            addProperty("channel", glyph.channel.name)
            addProperty("orientation", glyph.orientation.name)
            addProperty("oversized_width", glyph.oversizedRasterSize?.width)
            addProperty("oversized_height", glyph.oversizedRasterSize?.height)
        }

    private fun imageRecord(
        id: String,
        image: DrawImage?,
        copyBytes: Long?,
    ): JsonObject =
        JsonObject().apply {
            addProperty("id", id)
            addProperty("width", image?.size?.width)
            addProperty("height", image?.size?.height)
            image?.let {
                val bytes = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(it.size.width, it.size.height), 4))
                it.copyArgb().forEach(bytes::putInt)
                addProperty("argb_sha256", FontRasterAssets.digest(bytes.array()))
            }
            addProperty("eligible_copy_payload_bytes", copyBytes)
        }
}
