package dev.s7a.strata.runtime.minecraft.font

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.zip.ZipFile

/**
 * ZIP or JAR pack source that opens and closes an archive for each operation.
 * Files are read directly and never extracted to the filesystem.
 * The caller must not modify the archive during snapshot loading.
 *
 * @param archive existing archive file.
 * @property name diagnostic label.
 * @throws IllegalArgumentException when the archive is not a regular file.
 */
public class MinecraftArchiveFontAssetSource(
    archive: Path,
    override val name: String = archive.toString(),
) : MinecraftFontAssetSource {
    private val archive: Path = archive.toAbsolutePath().normalize()

    init {
        require(Files.isRegularFile(this.archive)) { "Font pack archive must be a regular file." }
    }

    override fun paths(): Set<String> =
        ZipFile(archive.toFile()).use { zip ->
            val paths =
                zip
                    .entries()
                    .asSequence()
                    .filter { entry -> entry.isDirectory.not() }
                    .map { entry -> entry.name.checkedFontSourcePath() }
                    .sorted()
                    .toList()
            Collections.unmodifiableSet(LinkedHashSet(paths))
        }

    override fun read(path: String): ByteArray? =
        ZipFile(archive.toFile()).use { zip ->
            val entry = zip.getEntry(path.checkedFontSourcePath()) ?: return@use null
            if (entry.isDirectory) return@use null
            zip.getInputStream(entry).use { input -> input.readBytes() }
        }
}
