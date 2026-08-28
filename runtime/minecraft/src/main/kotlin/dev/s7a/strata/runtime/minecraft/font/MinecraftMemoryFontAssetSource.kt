package dev.s7a.strata.runtime.minecraft.font

import java.util.Collections

/**
 * Immutable thread-safe in-memory pack source used by adapters and deterministic fixtures.
 * Construction copies all bytes and paths; reads always return a fresh array.
 *
 * @property name diagnostic label.
 * @param files canonical pack-relative files.
 * @throws IllegalArgumentException when a path is unsafe.
 */
public class MinecraftMemoryFontAssetSource(
    override val name: String,
    files: Map<String, ByteArray>,
) : MinecraftFontAssetSource {
    private val files: Map<String, ByteArray> =
        Collections.unmodifiableMap(files.mapKeys { (path, _) -> path.checkedFontSourcePath() }.mapValues { (_, bytes) -> bytes.copyOf() })
    private val filePaths: Set<String> = Collections.unmodifiableSet(LinkedHashSet(this.files.keys))

    override fun paths(): Set<String> = filePaths

    override fun read(path: String): ByteArray? = files[path.checkedFontSourcePath()]?.copyOf()
}
