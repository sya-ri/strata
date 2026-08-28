package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.DosFileAttributes

/**
 * Validates contained showcase paths without following symbolic links or reparse points.
 */
internal object ShowcasePaths {
    /**
     * Checks every existing segment from the filesystem root through the supplied leaf.
     *
     * @param path path whose ancestry must be safe.
     * @param label failure label for diagnostics.
     * @throws IllegalArgumentException when a segment is symbolic or reparse-backed.
     */
    internal fun requireSafeSegments(
        path: Path,
        label: String,
    ) {
        var current: Path? = path.toAbsolutePath().normalize()
        while (current != null) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isSymbolicLink(current).not()) { "$label has symbolic ancestry: $current" }
                require(isReparsePoint(current).not()) { "$label has reparse ancestry: $current" }
            }
            current = current.parent
        }
    }

    /**
     * Requires a path to be a non-symbolic directory.
     *
     * @param path path to inspect.
     * @param label failure label for diagnostics.
     * @throws IllegalArgumentException when the path is absent, linked, or not a directory.
     */
    internal fun requireDirectory(
        path: Path,
        label: String,
    ) {
        requireSafeSegments(path, label)
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "$label is not a directory: $path" }
        require(isReparsePoint(path).not()) { "$label is reparse-backed: $path" }
    }

    /**
     * Requires a read-only input to be an existing regular file without symbolic ancestry.
     *
     * @param path input file to inspect without opening it.
     * @param label failure label for diagnostics.
     * @throws IllegalArgumentException when the file is missing, linked, reparse-backed, or not regular.
     */
    internal fun requireRegularFile(
        path: Path,
        label: String,
    ) {
        requireSafeSegments(path, label)
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "$label is not a regular file: $path" }
    }

    /**
     * Requires a candidate to be a strict descendant of a safe root.
     *
     * @param root containment root.
     * @param candidate candidate path.
     * @param label failure label for diagnostics.
     * @return normalized candidate path.
     */
    internal fun contained(
        root: Path,
        candidate: Path,
        label: String,
    ): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val normalizedCandidate = candidate.toAbsolutePath().normalize()
        requireSafeSegments(normalizedRoot, label)
        requireSafeSegments(normalizedCandidate, label)
        require(normalizedCandidate != normalizedRoot && normalizedCandidate.startsWith(normalizedRoot)) {
            "$label path escapes its root: $candidate"
        }
        return normalizedCandidate
    }

    private fun isReparsePoint(path: Path): Boolean =
        try {
            Files.readAttributes(path, DosFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther
        } catch (_: NoSuchFileException) {
            false
        } catch (_: UnsupportedOperationException) {
            false
        }
}
