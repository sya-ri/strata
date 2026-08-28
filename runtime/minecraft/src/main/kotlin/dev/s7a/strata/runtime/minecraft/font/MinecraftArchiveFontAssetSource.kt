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
) : MinecraftBoundedFontAssetSource {
    private val archive: Path = archive.toAbsolutePath().normalize()

    init {
        require(Files.isRegularFile(this.archive)) { "Font pack archive must be a regular file." }
    }

    override fun paths(): Set<String> = paths(MinecraftFontLoadLimits())

    override fun paths(limits: MinecraftFontLoadLimits): Set<String> = paths(limits) {}

    override fun paths(
        limits: MinecraftFontLoadLimits,
        onEntryExamined: () -> Unit,
    ): Set<String> =
        open(limits).use { zip ->
            val paths =
                zip
                    .entries()
                    .asSequence()
                    .onEach { entry ->
                        onEntryExamined()
                        requireFontLimit(entry.name.length.toLong(), limits.maxPathLength.toLong(), "archive path length")
                    }.filter { entry -> entry.isDirectory.not() }
                    .map { entry -> entry.name.checkedFontSourcePath() }
                    .sorted()
                    .toList()
            Collections.unmodifiableSet(LinkedHashSet(paths))
        }

    override fun read(path: String): ByteArray? = read(path, MinecraftFontLoadLimits())

    override fun read(
        path: String,
        limits: MinecraftFontLoadLimits,
    ): ByteArray? =
        open(limits).use { zip ->
            requireFontLimit(path.length.toLong(), limits.maxPathLength.toLong(), "archive path length")
            val entry = zip.getEntry(path.checkedFontSourcePath()) ?: return@use null
            if (entry.isDirectory) return@use null
            requireFontLimit(entry.size, limits.maxAssetBytes.toLong(), "archive asset bytes")
            zip.getInputStream(entry).use { input -> input.readMinecraftFontBytes(limits.maxAssetBytes) }
        }

    private fun open(limits: MinecraftFontLoadLimits): ZipFile {
        requireFontLimit(Files.size(archive), limits.maxArchiveBytes, "compressed archive bytes")
        val zip = ZipFile(archive.toFile())
        return runCatching {
            requireFontLimit(zip.size().toLong(), limits.maxSourceEntries.toLong(), "archive entries")
            zip
        }.getOrElse { failure ->
            runCatching { zip.close() }.exceptionOrNull()?.let { cleanup -> if (cleanup !== failure) failure.addSuppressed(cleanup) }
            throw failure
        }
    }
}
