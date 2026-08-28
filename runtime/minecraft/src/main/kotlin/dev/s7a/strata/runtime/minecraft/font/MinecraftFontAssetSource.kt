package dev.s7a.strata.runtime.minecraft.font

/**
 * Synchronous source of pack-relative files for an immutable font snapshot.
 * Paths use forward slashes, including `assets/namespace/path` and optional `pack.mcmeta`.
 * A loader retains neither this source nor any returned mutable collection or array.
 * Implementations own no persistent open stream; callers must obey any source-specific thread contract.
 * Custom sources must bound allocation inside their callbacks; loaders also validate returned sizes before copying.
 * Implement [MinecraftBoundedFontAssetSource] to receive the current limits and stop while streaming or enumerating.
 */
public interface MinecraftFontAssetSource {
    /**
     * Stable diagnostic label, never used to choose provider behavior.
     */
    public val name: String

    /**
     * Lists available canonical pack-relative file paths.
     *
     * @return caller-readable paths without leading separators or parent traversal.
     * @throws Throwable when the source cannot be enumerated.
     */
    public fun paths(): Set<String>

    /**
     * Reads one complete file without retaining a stream.
     *
     * @param path canonical pack-relative lookup path, which may be absent from [paths].
     * @return fresh caller-owned bytes, or null when the file does not exist.
     * @throws IllegalArgumentException when the path is unsafe.
     * @throws Throwable when the source cannot be read.
     */
    public fun read(path: String): ByteArray?
}
