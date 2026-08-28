package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.render.DrawImage

/**
 * Owner-thread CPU image-decoding and TrueType services owned by one font engine.
 * Implementations must not require Minecraft, a display, or a graphics context.
 * Returned images and glyphs are detached, and close is idempotent.
 */
public interface MinecraftFontBackend : AutoCloseable {
    /**
     * Applies the selected release's native-compatible bidirectional ordering and shaping.
     * The default preserves text for backends whose caller supplies visual-order text.
     *
     * @param text immutable logical text.
     * @param rightToLeft whether the fallback paragraph direction is right-to-left.
     * @return an independent visual-order string.
     */
    public fun visualOrder(
        text: String,
        rightToLeft: Boolean,
    ): String = text

    /**
     * Orders and shapes a complete logical line while preserving native style lookup positions.
     * The default supplies identity order and replaces unmatched UTF-16 surrogates with the replacement scalar.
     * Backends providing native bidirectional processing must override this operation alongside [visualOrder].
     *
     * @param text immutable logical text, potentially containing several font spans.
     * @param rightToLeft whether the fallback paragraph direction is right-to-left.
     * @return detached immutable visual glyphs with UTF-16 offsets into the shaped logical line and original unadjusted style sequence.
     */
    public fun visualGlyphs(
        text: String,
        rightToLeft: Boolean,
    ): List<MinecraftVisualGlyph> =
        buildList {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                add(MinecraftVisualGlyph(if (codePoint in 0xD800..0xDFFF) 0xFFFD else codePoint, index))
                index += Character.charCount(codePoint)
            }
        }

    /**
     * Decodes PNG bytes into a detached straight-ARGB image.
     *
     * @param bytes caller-owned bytes that must not be retained or modified.
     * @return immutable decoded pixels.
     * @throws Throwable when the PNG cannot be decoded.
     */
    public fun decodePng(bytes: ByteArray): DrawImage

    /**
     * Opens one owner-thread TrueType face for the supplied settings.
     * A failed open must release every partially allocated face resource before propagating the failure.
     *
     * @param bytes caller-owned font bytes that must be copied if retained.
     * @param settings immutable provider rasterization settings.
     * @return a new face owned and closed by the caller.
     * @throws Throwable when the font cannot be opened.
     */
    public fun openTrueType(
        bytes: ByteArray,
        settings: MinecraftTrueTypeSettings,
    ): MinecraftTrueTypeFace

    /**
     * Releases backend-owned native resources on the owner thread.
     */
    override fun close()
}
