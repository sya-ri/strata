package dev.s7a.strata.runtime.minecraft.font

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/**
 * Directory-backed pack source that closes each enumeration and read before returning.
 * Reads reject symbolic links whose resolved target escapes the selected directory.
 * The caller must not modify the directory concurrently with snapshot loading.
 *
 * @param directory existing pack directory.
 * @property name diagnostic label.
 * @throws IllegalArgumentException when the directory is not a directory.
 * @throws Throwable when the directory cannot be resolved.
 */
public class MinecraftDirectoryFontAssetSource(
    directory: Path,
    override val name: String = directory.toString(),
) : MinecraftBoundedFontAssetSource {
    private val directory: Path = directory.toRealPath()

    init {
        require(Files.isDirectory(this.directory)) { "Font pack source must be a directory." }
    }

    override fun paths(): Set<String> = paths(MinecraftFontLoadLimits())

    override fun paths(limits: MinecraftFontLoadLimits): Set<String> = paths(limits) {}

    override fun paths(
        limits: MinecraftFontLoadLimits,
        onEntryExamined: () -> Unit,
    ): Set<String> =
        Files.walk(directory).use { entries ->
            val paths = ArrayList<String>()
            var count = 0L
            entries.forEach { entry ->
                if (entry != directory) {
                    onEntryExamined()
                    requireFontLimit(++count, limits.maxSourceEntries.toLong(), "directory entries")
                    val path = directory.relativize(entry).joinToString("/")
                    requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "directory path length")
                    if (Files.isRegularFile(entry)) paths.add(path)
                }
            }
            Collections.unmodifiableSet(LinkedHashSet(paths.sorted()))
        }

    override fun read(path: String): ByteArray? = read(path, MinecraftFontLoadLimits())

    override fun read(
        path: String,
        limits: MinecraftFontLoadLimits,
    ): ByteArray? {
        requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "directory path length")
        val candidate = directory.resolve(path.checkedFontSourcePath()).normalize()
        require(candidate.startsWith(directory)) { "Font asset path escapes its pack directory." }
        if (Files.exists(candidate).not()) return null
        val resolved = candidate.toRealPath()
        require(resolved.startsWith(directory)) { "Font asset symbolic link escapes its pack directory." }
        if (Files.isRegularFile(resolved).not()) return null
        requireFontLimit(Files.size(resolved), limits.maxAssetBytes.toLong(), "directory asset bytes")
        return Files.newInputStream(resolved).use { input -> input.readMinecraftFontBytes(limits.maxAssetBytes) }
    }
}
