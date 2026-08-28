package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.runtime.minecraft.font.MinecraftFontEngine
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontSnapshot
import dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Encodes exact native provider metrics and raw texel hashes for a later independent CPU process.
 * Only detached values are persisted; no captured glyph is supplied to the portable renderer.
 * Methods own temporary buffers and close any newly opened engine before returning.
 */
internal object MinecraftFontGlyphEvidence {
    /**
     * Returns exact float-bit and raw-ARGB hashes for one independently loaded native provider result.
     */
    fun entries(
        font: MinecraftFontParityFixture.FontCase,
        codePoint: Int,
        glyph: MinecraftFontGlyph,
    ): Map<String, String> {
        val key = "glyph.${font.id.path}.${codePoint.toString(16)}"
        val metrics = listOf(glyph.advance, glyph.left, glyph.top, glyph.right, glyph.bottom, glyph.boldOffset, glyph.shadowOffset)
        val result =
            linkedMapOf(
                "$key.metrics" to metrics.joinToString(",") { it.toRawBits().toUInt().toString(16) },
                "$key.channel" to glyph.channel.name,
                "$key.orientation" to glyph.orientation.name,
            )
        val image = glyph.image
        if (image == null) {
            result["$key.image"] = "none"
        } else {
            val raw = ByteBuffer.allocate(Math.multiplyExact(Math.multiplyExact(image.size.width, image.size.height), Int.SIZE_BYTES))
            image.copyArgb().forEach(raw::putInt)
            result["$key.image"] = "${image.size.width},${image.size.height}"
            result["$key.argb.sha256"] = MinecraftFontParityFixture.sha256(raw.array())
        }
        return result
    }

    /**
     * Recomputes every provider result from original snapshot bytes and requires exact equality to native evidence.
     * Missing keys, extra keys, changed metrics, orientation, dimensions, or texels fail without any pixel tolerance.
     */
    fun verify(
        snapshot: MinecraftFontSnapshot,
        path: Path,
    ) {
        val expected = linkedMapOf<String, String>()
        Files.readAllLines(path).filter { it.isNotEmpty() }.forEach { line ->
            val separator = line.indexOf('=')
            check(0 < separator) { "Malformed native glyph evidence: $path" }
            check(expected.put(line.substring(0, separator), line.substring(separator + 1)) == null) { "Duplicate native glyph evidence key." }
        }
        val actual = linkedMapOf<String, String>()
        MinecraftFontEngine(snapshot, LwjglMinecraftFontBackendFactory).use { engine ->
            MinecraftFontParityFixture.FontCase.entries.filter { it.providerIndex != null }.forEach { font ->
                font.codePoints.forEach { codePoint -> actual.putAll(entries(font, codePoint, engine.glyph(font.id, codePoint))) }
            }
        }
        check(expected == actual) { "Independent offline provider metrics or raw glyph pixels differ from the native evidence." }
    }
}
