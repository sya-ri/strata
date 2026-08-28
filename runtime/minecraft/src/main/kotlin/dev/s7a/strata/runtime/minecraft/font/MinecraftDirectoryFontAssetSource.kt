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
) : MinecraftFontAssetSource {
    private val directory: Path = directory.toRealPath()

    init {
        require(Files.isDirectory(this.directory)) { "Font pack source must be a directory." }
    }

    override fun paths(): Set<String> =
        Files.walk(directory).use { entries ->
            val paths =
                entries
                    .filter { entry -> Files.isRegularFile(entry) }
                    .map { entry -> directory.relativize(entry).joinToString("/") }
                    .sorted()
                    .toList()
            Collections.unmodifiableSet(LinkedHashSet(paths))
        }

    override fun read(path: String): ByteArray? {
        val candidate = directory.resolve(path.checkedFontSourcePath()).normalize()
        require(candidate.startsWith(directory)) { "Font asset path escapes its pack directory." }
        if (Files.exists(candidate).not()) return null
        val resolved = candidate.toRealPath()
        require(resolved.startsWith(directory)) { "Font asset symbolic link escapes its pack directory." }
        return if (Files.isRegularFile(resolved)) Files.readAllBytes(resolved) else null
    }
}
