package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontCompatibility
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.MinecraftMemoryFontAssetSource
import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Stable bitmap resource-font input, including the showcase's multilingual labels.
 * Bitmap decoding and graph loading run only during fixture preparation, never inside a JMH operation.
 */
internal object ComponentFontAssets {
    /**
     * Prepares detached synthetic PNG/JSON bytes for the real resource-font loader.
     */
    internal fun source(): MinecraftMemoryFontAssetSource {
        val codePoints = ((32..126).toList() + "日本語한글🙂…設定選択入力表示".codePoints().toArray().toList()).distinct()
        val rows = codePoints.chunked(16).map { row -> String(row.toIntArray(), 0, row.size) + "\u0000".repeat(16 - row.size) }
        val image = BufferedImage(128, rows.size * 8, BufferedImage.TYPE_INT_ARGB)
        codePoints.forEachIndexed { index, codePoint ->
            val x = index % 16 * 8
            val y = index / 16 * 8
            if (codePoint != 32) {
                for (column in 0..4) {
                    for (line in 0..6) image.setRGB(x + column, y + line, -1)
                }
            }
        }
        val pixels =
            ByteArrayOutputStream().use { output ->
                check(ImageIO.write(image, "PNG", output)) { "The bitmap font PNG encoder is unavailable" }
                output.toByteArray()
            }
        val provider =
            JsonObject().apply {
                addProperty("type", "bitmap")
                addProperty("file", "strata_benchmark:font/glyphs.png")
                addProperty("ascent", 7)
                addProperty("height", 8)
                add("chars", JsonArray().apply { rows.forEach(::add) })
            }
        val definition = JsonObject().apply { add("providers", JsonArray().apply { add(provider) }) }.toString().toByteArray(Charsets.UTF_8)
        val source =
            MinecraftMemoryFontAssetSource(
                "component-bitmap-v1",
                mapOf("assets/minecraft/font/default.json" to definition, "assets/strata_benchmark/textures/font/glyphs.png" to pixels),
            )
        return source
    }

    /**
     * Loads one prepared detached bitmap source for the existing component corpus.
     */
    internal fun snapshot(): MinecraftFontSnapshot =
        MinecraftFontSnapshot.load(listOf(source()), MinecraftFontCompatibility(MinecraftTrueTypeRasterizer.FreeType, 84, fractionalUnihexAdvance = true, rejectMalformedOverlayMetadata = true)).also { snapshot ->
            check(snapshot.diagnostics.isEmpty()) { "Synthetic component font preparation failed: ${snapshot.diagnostics}" }
        }
}
