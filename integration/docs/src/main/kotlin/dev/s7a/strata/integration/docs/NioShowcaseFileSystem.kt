package dev.s7a.strata.integration.docs

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Comparator

/**
 * Production filesystem boundary for showcase synchronization.
 *
 * Every operation validates paths without following links.
 * Tree copies read and write bytes explicitly, so file attributes never cross the synchronization boundary.
 * Operations are synchronous, do not retain paths, and propagate provider failures to the transaction owner.
 */
internal object NioShowcaseFileSystem : ShowcaseFileSystem {
    /**
     * Copies a safe directory tree without copying file attributes.
     *
     * @param source existing staged directory.
     * @param target absent destination directory.
     */
    override fun copy(
        source: Path,
        target: Path,
    ) {
        requireDirectory(source, "copy source")
        requireAbsent(target, "copy target")
        Files.walk(source).use { stream ->
            stream.forEach { path ->
                requireSafe(path, "copy source entry")
                val relative = source.relativize(path)
                val destination = target.resolve(relative.toString()).normalize()
                require(destination.startsWith(target)) { "Copy entry escapes target: $path" }
                requireSafe(destination, "copy destination")
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(destination)
                } else {
                    require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        "Copy source entry is not regular: $path"
                    }
                    Files.createDirectories(destination.parent)
                    Files.write(
                        destination,
                        Files.readAllBytes(path),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE,
                    )
                }
            }
        }
    }

    /**
     * Writes exact bytes to a safe absent file.
     *
     * @param path destination file.
     * @param bytes exact file contents.
     */
    override fun write(
        path: Path,
        bytes: ByteArray,
    ) {
        requireSafe(path, "write destination")
        requireAbsent(path, "write destination")
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
    }

    /**
     * Moves one safe path, preferring an atomic move when supported.
     *
     * @param source source path.
     * @param target absent destination path.
     */
    override fun move(
        source: Path,
        target: Path,
    ) {
        requireSafe(source, "move source")
        requireSafe(target, "move destination")
        require(Files.exists(source, LinkOption.NOFOLLOW_LINKS)) { "Move source is missing: $source" }
        requireAbsent(target, "move destination")
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    /**
     * Deletes one safe regular file when it exists.
     *
     * @param path file path.
     */
    override fun delete(path: Path) {
        requireSafe(path, "file deletion")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "File deletion target is not regular: $path" }
            Files.delete(path)
        }
    }

    /**
     * Deletes one safe directory tree when it exists.
     *
     * @param path directory path.
     */
    override fun deleteTree(path: Path) {
        requireSafe(path, "tree deletion")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS).not()) return
        requireDirectory(path, "tree deletion")
        Files.walk(path).use { stream ->
            val entries = stream.toList()
            entries.forEach { entry -> requireSafe(entry, "tree deletion entry") }
            entries
                .sortedWith(Comparator.reverseOrder())
                .filter { entry -> entry != path }
                .forEach { entry ->
                    require(Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                        "Tree deletion entry is not regular: $entry"
                    }
                    Files.delete(entry)
                }
            Files.delete(path)
        }
    }

    private fun requireDirectory(
        path: Path,
        label: String,
    ) {
        requireSafe(path, label)
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "$label is not a directory: $path" }
    }

    private fun requireAbsent(
        path: Path,
        label: String,
    ) {
        require(Files.exists(path, LinkOption.NOFOLLOW_LINKS).not()) { "$label already exists: $path" }
    }

    private fun requireSafe(
        path: Path,
        label: String,
    ) = ShowcasePaths.requireSafeSegments(path, label)
}
