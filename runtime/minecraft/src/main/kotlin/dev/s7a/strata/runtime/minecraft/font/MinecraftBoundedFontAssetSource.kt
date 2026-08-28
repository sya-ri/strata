package dev.s7a.strata.runtime.minecraft.font

/**
 * Optional streaming-capable source contract that enforces limits before allocating complete results.
 * Existing [MinecraftFontAssetSource] implementations keep their original callbacks, including interception through Kotlin delegation.
 * Implementations own and close their streams synchronously and retain no mutable loading counter.
 */
public interface MinecraftBoundedFontAssetSource : MinecraftFontAssetSource {
    /**
     * Lists paths under inclusive entry and path ceilings, checking while enumerating where possible.
     * The default checks an already bounded original [paths] result before loader copying.
     *
     * @param limits immutable ceilings, tightened to remaining aggregate entry capacity.
     * @return caller-readable canonical paths, never retained by a snapshot.
     * @throws MinecraftFontLoadLimitException when a ceiling is exceeded; interrupted enumeration consumes its allowance and detection entry.
     * @throws Throwable when ordinary enumeration fails; those failures are not converted into budget diagnostics.
     */
    public fun paths(limits: MinecraftFontLoadLimits): Set<String> = checkedFontPaths(paths(), limits)

    /**
     * Enumerates paths while reporting each examined entry before validation or filtering.
     * Directory and ignored entries count even when absent from the returned set; implementations must stop if the callback throws.
     * The callback is synchronous, owner-thread-only, and must not be retained; late or foreign-thread calls fail before changing loader counters.
     * The default reports the bounded original result, so sources that filter physical entries must override this method.
     *
     * @param limits immutable ceilings tightened to remaining snapshot capacity.
     * @param onEntryExamined synchronous callback that charges one entry and may throw when aggregate capacity is exhausted.
     * @return canonical file paths without transferring stream ownership.
     * @throws Throwable when enumeration, validation, or the callback fails; owned streams must still close.
     */
    public fun paths(
        limits: MinecraftFontLoadLimits,
        onEntryExamined: () -> Unit,
    ): Set<String> = paths(limits).onEach { _ -> onEntryExamined() }

    /**
     * Reads one asset under inclusive encoded-byte and path ceilings.
     * Streaming implementations must stop before allocating oversized input and close streams on success and failure.
     * The default checks the already bounded original [read] result before a loader copy.
     *
     * @param path canonical source-relative path, which may be absent from [paths].
     * @param limits immutable limits tightened to remaining snapshot byte capacity.
     * @return fresh caller-owned bytes or null for an absent file.
     * @throws Throwable when reading or a ceiling check fails.
     */
    public fun read(
        path: String,
        limits: MinecraftFontLoadLimits,
    ): ByteArray? {
        requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "asset path length")
        return read(path)?.also { bytes -> requireFontLimit(bytes.size.toLong(), limits.maxAssetBytes.toLong(), "asset bytes") }
    }
}
