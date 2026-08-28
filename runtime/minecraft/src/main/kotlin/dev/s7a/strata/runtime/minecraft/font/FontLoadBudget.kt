package dev.s7a.strata.runtime.minecraft.font

/**
 * Loader-thread counters shared by every source, document, provider, and Unihex stream in one snapshot operation.
 * Counters and borrowed sources are never retained by the resulting snapshot.
 *
 * @property limits immutable ceilings selected by the caller.
 */
internal class FontLoadBudget(
    val limits: MinecraftFontLoadLimits,
) {
    private val consumed = LongArray(Kind.entries.size)

    /**
     * Reserves an inclusive quantity before the corresponding allocation or collection expansion.
     * Observed work saturates its counter on overflow, preventing repeated rejected input from resetting aggregate capacity.
     *
     * @param kind typed counter to charge.
     * @param amount non-negative additional quantity.
     * @throws IllegalArgumentException when the remaining capacity is insufficient.
     */
    fun claim(
        kind: Kind,
        amount: Long,
    ) {
        require(0 <= amount) { "Font budget reservations must be non-negative." }
        val available = remaining(kind)
        if (available < amount) {
            consumed[kind.ordinal] = maximum(kind)
            requireFontLimit(amount, available, kind.name)
        }
        consumed[kind.ordinal] += amount
    }

    /**
     * Returns unconsumed capacity for a typed counter without mutating ownership.
     */
    fun remaining(kind: Kind): Long = maximum(kind) - consumed[kind.ordinal]

    /**
     * Enumerates one source under remaining entry capacity, counting ignored physical entries when the bounded SPI reports them.
     * Old sources keep their original callback and are charged before copying or validating the returned paths.
     * A typed failure before any entry report conservatively consumes the allowance and one detection entry.
     *
     * @param source caller-owned stable source, never retained by this operation.
     * @return borrowed bounded paths for immediate loader copying.
     * @throws Throwable when enumeration, validation, or aggregate accounting fails.
     */
    fun paths(source: MinecraftFontAssetSource): Set<String> {
        val allowance = minOf(limits.maxSourceEntries.toLong(), remaining(Kind.SourceEntries))
        val enumerationLimits = limits.copy(maxSourceEntries = allowance.toInt())
        return FontEntryObserver(this, allowance).use { observer ->
            runCatching {
                val result = if (source is MinecraftBoundedFontAssetSource) source.paths(enumerationLimits, observer) else source.paths()
                observer.complete(result.size)
                checkedFontPaths(result, enumerationLimits)
            }.getOrElse { failure ->
                if (failure is MinecraftFontLoadLimitException && observer.observed == 0L) claim(Kind.SourceEntries, allowance + 1)
                throw failure
            }
        }
    }

    /**
     * Reads an asset with the smaller of its per-file ceiling and the remaining snapshot byte budget.
     * Existing custom sources are checked after their callback returns and before a detached resource copy is made.
     *
     * @param source callback-lifetime source, never retained by this operation.
     * @param path canonical source-relative lookup path.
     * @param document whether the smaller JSON document ceiling also applies.
     * @return caller-owned bytes or null when no file exists.
     * @throws Throwable when reading or a budget check fails.
     */
    fun read(
        source: MinecraftFontAssetSource,
        path: String,
        document: Boolean = false,
    ): ByteArray? {
        requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "asset path length")
        val maximum = minOf(limits.maxAssetBytes.toLong(), remaining(Kind.InputBytes), if (document) limits.maxDocumentBytes.toLong() else Long.MAX_VALUE).toInt()
        val bytes =
            runCatching {
                if (source is MinecraftBoundedFontAssetSource) source.read(path, limits.copy(maxAssetBytes = maximum)) else source.read(path)
            }.getOrElse { failure ->
                if (failure is Exception) claim(Kind.InputBytes, minOf(maximum.toLong() + 1, remaining(Kind.InputBytes)))
                throw failure
            } ?: return null
        claim(Kind.InputBytes, bytes.size.toLong())
        requireFontLimit(bytes.size.toLong(), maximum.toLong(), "asset bytes")
        return bytes
    }

    private fun maximum(kind: Kind): Long =
        when (kind) {
            Kind.SourceEntries -> limits.maxEntries.toLong()
            Kind.FontDocuments -> limits.maxFontDocuments.toLong()
            Kind.Providers -> limits.maxProviders.toLong()
            Kind.Glyphs -> limits.maxGlyphs.toLong()
            Kind.GlyphRowBytes -> limits.maxGlyphRowBytes
            Kind.InputBytes -> limits.maxInputBytes
            Kind.DecompressedBytes -> limits.maxDecompressedBytes
            Kind.ResolvedProviders -> limits.maxResolvedProviders.toLong()
            Kind.TrueTypeFaces -> limits.maxTrueTypeFaces.toLong()
            Kind.TrueTypeInputBytes -> limits.maxTrueTypeInputBytes
        }

    /**
     * Independent payload and record counters used by the loader's allocation boundaries.
     */
    enum class Kind {
        /**
         * Source paths and nested archive entries processed across packs, including rejected sources.
         */
        SourceEntries,

        /**
         * Selected font document locations across accepted packs and overlays.
         */
        FontDocuments,

        /**
         * Declared provider records before parsing their individual settings.
         */
        Providers,

        /**
         * Explicit scalar records and mapping cells, including repeated records.
         */
        Glyphs,

        /**
         * Sixteen-Long payload reserved for each decoded Unihex record.
         */
        GlyphRowBytes,

        /**
         * Encoded asset and document bytes returned by accepted source reads.
         */
        InputBytes,

        /**
         * Expanded bytes consumed from Unihex entries and PNG data, plus inspected PNG pixel payloads.
         */
        DecompressedBytes,

        /**
         * Provider entries copied into resolved font-reference results.
         */
        ResolvedProviders,

        /**
         * Unique resource-and-settings face descriptors, independent of provider skips and filters.
         */
        TrueTypeFaces,

        /**
         * Encoded input payload charged once for each unique native face descriptor before opening a backend.
         */
        TrueTypeInputBytes,
    }
}
