package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.minecraft.font.MinecraftFontGlyph
import java.nio.ByteBuffer

/**
 * Detached native provider observation before normalization, atlas fallback, or width conversion.
 * Float bit patterns retain signed zero, infinity, and NaN; pixels are optional and never supplied to a candidate renderer.
 * Positive source dimensions are measured without allocation when either exceeds the native atlas extent.
 */
internal data class MinecraftNumericFontGlyph(
    val present: Boolean,
    val advance: Float = 0f,
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
    val boldOffset: Float = 1f,
    val shadowOffset: Float = 1f,
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val image: DrawImage? = null,
) {
    init {
        require(0 <= sourceWidth && 0 <= sourceHeight)
        require(image == null || (sourceWidth in 1..256 && sourceHeight in 1..256)) { "The numeric oracle must never allocate an oversized native glyph." }
        require(image == null || (image.size.width == sourceWidth && image.size.height == sourceHeight))
    }

    /**
     * Encodes every raw bit and source dimension under an explicit case/code-point identity.
     */
    fun entries(
        case: MinecraftNumericFontFixture.Case,
        codePoint: Int,
    ): Map<String, String> {
        val key = "glyph.${case.name}.${codePoint.toString(16)}"
        val result =
            linkedMapOf(
                "$key.present" to present.toString(),
                "$key.metrics" to listOf(advance, left, top, right, bottom, boldOffset, shadowOffset).joinToString(",") { it.toRawBits().toUInt().toString(16) },
                "$key.source" to "$sourceWidth,$sourceHeight",
            )
        val pixels = image?.copyArgb()
        result["$key.argb.sha256"] =
            if (pixels == null) {
                "none"
            } else {
                val bytes = ByteBuffer.allocate(Math.multiplyExact(pixels.size, Int.SIZE_BYTES))
                pixels.forEach(bytes::putInt)
                MinecraftFontParityFixture.sha256(bytes.array())
            }
        return result
    }

    /**
     * Reconstructs the original provider axes from the CPU backend's explicit orientation, without changing any float.
     */
    companion object {
        /**
         * Converts a detached raw backend result; the common engine's later atlas fallback is intentionally not involved.
         */
        fun from(glyph: MinecraftFontGlyph?): MinecraftNumericFontGlyph {
            if (glyph == null) return MinecraftNumericFontGlyph(false)
            val size = glyph.image?.size ?: glyph.oversizedRasterSize
            return MinecraftNumericFontGlyph(
                true,
                glyph.advance,
                if (glyph.orientation.flipX) glyph.right else glyph.left,
                if (glyph.orientation.flipY) glyph.bottom else glyph.top,
                if (glyph.orientation.flipX) glyph.left else glyph.right,
                if (glyph.orientation.flipY) glyph.top else glyph.bottom,
                glyph.boldOffset,
                glyph.shadowOffset,
                size?.width ?: 0,
                size?.height ?: 0,
                glyph.image,
            )
        }
    }
}
