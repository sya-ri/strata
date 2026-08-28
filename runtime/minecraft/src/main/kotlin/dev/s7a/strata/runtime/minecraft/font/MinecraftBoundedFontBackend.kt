package dev.s7a.strata.runtime.minecraft.font

import dev.s7a.strata.render.DrawImage

/**
 * Optional backend capability that inspects image dimensions before allocating native or JVM pixels.
 * Ownership and owner-thread confinement follow [MinecraftFontBackend]; existing backends keep their original callbacks.
 */
public interface MinecraftBoundedFontBackend : MinecraftFontBackend {
    /**
     * Decodes one image under the snapshot's encoded-byte, dimension, and pixel-payload ceilings.
     * Implementations must inspect dimensions before native allocation and release partial resources after any failure.
     * Callers independently check the returned size before retaining or copying image data.
     *
     * @param bytes caller-owned input, never retained or modified.
     * @param limits immutable loading and image-allocation ceilings.
     * @return detached immutable pixels within the selected ceilings.
     * @throws Throwable when input, decoding, or a ceiling check fails.
     */
    public fun decodePng(
        bytes: ByteArray,
        limits: MinecraftFontLoadLimits,
    ): DrawImage

    /**
     * Opens one owner-thread TrueType face under immutable encoded-byte and glyph-image ceilings.
     * The default validates the encoded byte count before delegating to the existing face callback.
     * Native backends override this method to pin the selected limits to the face and check measured glyph dimensions before allocating raster pixels.
     * An oversized native-atlas marker allocates no pixels and is not itself subject to the image-allocation ceilings.
     * Callers independently validate returned images before retaining them, including images from legacy implementations.
     *
     * @param bytes caller-owned font bytes, copied if retained by the returned face.
     * @param settings immutable provider rasterization settings.
     * @param limits immutable ceilings retained by the new face when bounded rasterization is implemented.
     * @return a new face owned and closed by the caller on the opening thread.
     * @throws Throwable when a ceiling, initialization, or later rasterization check fails; failed initialization releases partial resources.
     */
    public fun openTrueType(
        bytes: ByteArray,
        settings: MinecraftTrueTypeSettings,
        limits: MinecraftFontLoadLimits,
    ): MinecraftTrueTypeFace {
        requireFontLimit(bytes.size.toLong(), limits.maxAssetBytes.toLong(), "TrueType asset bytes")
        return openTrueType(bytes, settings)
    }
}
