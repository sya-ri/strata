package dev.s7a.strata.runtime.minecraft.font

import java.util.Collections

/**
 * Immutable thread-safe in-memory pack source used by adapters and deterministic fixtures.
 * Construction copies all bytes and paths; reads always return a fresh array.
 *
 * @property name diagnostic label.
 * @param files canonical pack-relative files.
 * @param limits byte and entry ceilings checked before copying caller-owned arrays.
 * @throws IllegalArgumentException when a path is unsafe.
 */
public class MinecraftMemoryFontAssetSource(
    override val name: String,
    files: Map<String, ByteArray>,
    limits: MinecraftFontLoadLimits,
) : MinecraftBoundedFontAssetSource {
    private val files: Map<String, ByteArray>

    init {
        checkedFontPaths(files.keys, limits)
        var total = 0L
        files.values.forEach { bytes ->
            requireFontLimit(bytes.size.toLong(), limits.maxAssetBytes.toLong(), "in-memory asset bytes")
            requireFontLimit(bytes.size.toLong(), limits.maxInputBytes - total, "in-memory source bytes")
            total += bytes.size
        }
        this.files = Collections.unmodifiableMap(files.mapKeys { (path, _) -> path.checkedFontSourcePath() }.mapValues { (_, bytes) -> bytes.copyOf() })
    }

    private val filePaths: Set<String> = Collections.unmodifiableSet(LinkedHashSet(this.files.keys))

    /**
     * Copies an in-memory source using default ceilings while retaining the existing constructor signature.
     * Inputs, ownership, and failures follow the primary constructor.
     */
    public constructor(name: String, files: Map<String, ByteArray>) : this(name, files, MinecraftFontLoadLimits())

    override fun paths(): Set<String> = filePaths

    override fun read(path: String): ByteArray? = files[path.checkedFontSourcePath()]?.copyOf()

    override fun read(
        path: String,
        limits: MinecraftFontLoadLimits,
    ): ByteArray? {
        requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "in-memory path length")
        val bytes = files[path.checkedFontSourcePath()] ?: return null
        requireFontLimit(bytes.size.toLong(), limits.maxAssetBytes.toLong(), "in-memory asset bytes")
        return bytes.copyOf()
    }
}
