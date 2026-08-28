package dev.s7a.strata.runtime.minecraft.font

/**
 * Immutable input and allocation ceilings for one synchronous font snapshot and its image decoders.
 * Counts include rejected records already processed, and byte ceilings describe payload rather than total JVM heap usage.
 * Defaults accommodate the measured vanilla resources of every supported release; callers may choose tighter or larger finite budgets.
 * No source, stream, mutable counter, or native resource is retained here, so limits may be shared across threads.
 *
 * @property maxSources source count accepted by one snapshot invocation.
 * @property maxSourceEntries entries examined in one source or nested Unihex archive, including archive directories.
 * @property maxEntries aggregate source paths and nested ZIP entries processed by one snapshot, including rejected sources.
 * @property maxPathLength maximum UTF-16 length of one source-relative path or archive entry name.
 * @property maxArchiveBytes maximum compressed ZIP or JAR file size before opening its directory.
 * @property maxAssetBytes maximum bytes returned for one encoded asset, also bounding all concurrently retained native face input payloads in one engine.
 * @property maxDocumentBytes maximum bytes in a font document, pack metadata, or asset index.
 * @property maxInputBytes aggregate encoded bytes read while loading a snapshot or copying an in-memory source.
 * @property maxDecompressedEntryBytes maximum expanded bytes in one Unihex ZIP entry or PNG image-data stream, including ignored ZIP entries.
 * @property maxDecompressedBytes aggregate expanded Unihex and PNG image-data bytes plus PNG four-byte pixel payloads examined by a snapshot.
 * @property maxFontDocuments total font document locations accepted across the source stack.
 * @property maxProviders total declared provider records processed across font documents.
 * @property maxGlyphs total explicit scalar records processed, including duplicate Unihex records, bitmap cells, advances, and skip lists.
 * @property maxGlyphRowBytes aggregate retained Unihex row payload, charged as sixteen Long values per record before allocation.
 * @property maxJsonDepth maximum JSON container nesting before tree parsing.
 * @property maxJsonValues maximum JSON tokens examined in one document before tree parsing.
 * @property maxReferenceDepth maximum active font-reference recursion depth.
 * @property maxResolvedProviders aggregate provider entries produced by reference expansion.
 * @property maxImageDimension maximum width or height before allocating a decoded sheet or glyph image.
 * @property maxImageBytes maximum four-byte pixel payload of one decoded sheet or glyph image.
 * @property maxBitmapSheetBytes independent input ceiling for a bitmap provider's decoded sheet, unaffected by engine cache settings.
 * @property maxTrueTypeFaces maximum distinct resource-and-settings descriptors examined by a snapshot, independent of provider skips and filters.
 * @property maxTrueTypeInputBytes aggregate encoded face input charged once for every distinct resource-and-settings descriptor, before native work.
 * @throws IllegalArgumentException when a ceiling is negative or a depth or path ceiling is zero.
 */
public data class MinecraftFontLoadLimits(
    public val maxSources: Int = 256,
    public val maxSourceEntries: Int = 65_536,
    public val maxEntries: Int = 262_144,
    public val maxPathLength: Int = 1_024,
    public val maxArchiveBytes: Long = 256L * 1024 * 1024,
    public val maxAssetBytes: Int = 32 * 1024 * 1024,
    public val maxDocumentBytes: Int = 2 * 1024 * 1024,
    public val maxInputBytes: Long = 128L * 1024 * 1024,
    public val maxDecompressedEntryBytes: Long = 32L * 1024 * 1024,
    public val maxDecompressedBytes: Long = 128L * 1024 * 1024,
    public val maxFontDocuments: Int = 4_096,
    public val maxProviders: Int = 16_384,
    public val maxGlyphs: Int = 1_048_576,
    public val maxGlyphRowBytes: Long = 128L * 1024 * 1024,
    public val maxJsonDepth: Int = 64,
    public val maxJsonValues: Int = 1_048_576,
    public val maxReferenceDepth: Int = 128,
    public val maxResolvedProviders: Int = 65_536,
    public val maxImageDimension: Int = 8_192,
    public val maxImageBytes: Long = 64L * 1024 * 1024,
    public val maxBitmapSheetBytes: Long = 8L * 1024 * 1024,
    public val maxTrueTypeFaces: Int = 256,
    public val maxTrueTypeInputBytes: Long = 128L * 1024 * 1024,
) {
    init {
        require(listOf(maxSources, maxSourceEntries, maxEntries, maxAssetBytes, maxDocumentBytes, maxFontDocuments, maxProviders, maxGlyphs, maxJsonValues, maxResolvedProviders, maxImageDimension, maxTrueTypeFaces).all { value -> 0 <= value }) {
            "Font loading count ceilings must be non-negative."
        }
        require(listOf(maxArchiveBytes, maxInputBytes, maxDecompressedEntryBytes, maxDecompressedBytes, maxGlyphRowBytes, maxImageBytes, maxBitmapSheetBytes, maxTrueTypeInputBytes).all { value -> 0 <= value }) {
            "Font loading byte ceilings must be non-negative."
        }
        require(0 < maxPathLength && 0 < maxJsonDepth && 0 < maxReferenceDepth) { "Font loading path and depth ceilings must be positive." }
    }

    /**
     * Rejects invalid or oversized image dimensions before native decoding or pixel-array allocation.
     * Multiplication uses Long arithmetic; the four-byte payload must fit an Int-indexed native byte buffer even with larger custom limits.
     *
     * @param width source image width, which may be zero for an empty image.
     * @param height source image height, which may be zero for an empty image.
     * @throws IllegalArgumentException when dimensions or pixel payload exceed the selected ceilings.
     */
    public fun requireImageSize(
        width: Int,
        height: Int,
    ) {
        require(0 <= width && 0 <= height) { "Font image dimensions must be non-negative." }
        requireFontLimit(width.toLong(), maxImageDimension.toLong(), "image width")
        requireFontLimit(height.toLong(), maxImageDimension.toLong(), "image height")
        val pixels = width.toLong() * height
        requireFontLimit(pixels, Int.MAX_VALUE.toLong() / 4, "image native byte-buffer capacity")
        requireFontLimit(pixels, maxImageBytes / 4, "image four-byte pixel payload")
    }

    /**
     * Applies generic image safety and the independent bitmap-sheet input ceiling before allocating a provider sheet.
     * The default sheet ceiling allows the default raster cache to retain a sheet and its largest copied glyph together.
     * Raising this input ceiling may cause repeated decoding under a smaller cache, but never changes provider selection between cache modes.
     *
     * @param width decoded sheet width.
     * @param height decoded sheet height.
     * @throws MinecraftFontLoadLimitException when the sheet exceeds an image or bitmap input ceiling.
     */
    public fun requireBitmapSheetSize(
        width: Int,
        height: Int,
    ) {
        requireImageSize(width, height)
        requireFontLimit(width.toLong() * height, maxBitmapSheetBytes / 4, "bitmap sheet four-byte pixel payload")
    }

    /**
     * Checks recognizable PNG dimensions and inflates image data into a fixed scratch buffer before native decoding.
     * No decoded pixels are retained, and malformed PNG data or excessive expansion fails before native allocation.
     * Other encoded formats return false and remain subject to a decoder's own format and allocation contract.
     *
     * @param bytes caller-owned encoded bytes, read synchronously without retaining them.
     * @return true for a PNG whose preflight completed, or false when the PNG signature is absent.
     * @throws IllegalArgumentException when recognizable PNG data is malformed or a ceiling is exceeded.
     */
    public fun checkPng(bytes: ByteArray): Boolean = FontPngBounds.check(bytes, this) {}
}
