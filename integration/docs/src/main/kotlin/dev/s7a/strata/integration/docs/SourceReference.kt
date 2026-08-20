package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * A typed, repository-contained source file reference for one showcase region.
 *
 * @property relativePath source path relative to the source root.
 * @property slug marker slug expected in that file.
 */
internal class SourceReference internal constructor(
    internal val relativePath: String,
    internal val slug: String,
) {
    /**
     * Resolves this reference below a trusted source root without following links.
     *
     * @param sourceRoot source root selected by the launcher.
     * @return the regular source file path.
     * @throws IllegalArgumentException when the reference escapes or is not regular.
     */
    internal fun resolve(sourceRoot: Path): Path {
        val relative = Path.of(relativePath)
        require(relative.isAbsolute.not()) { "Showcase source path must be relative: $relativePath" }
        require(relative.normalize() == relative && relative.startsWith(Path.of("..")).not()) {
            "Showcase source path must not contain parent traversal: $relativePath"
        }
        require(slug.matches(Regex("[a-z][a-z0-9-]*"))) { "Showcase source slug is malformed: $slug" }
        val root = sourceRoot.toAbsolutePath().normalize()
        ShowcasePaths.requireSafeSegments(root, "Showcase source root")
        val resolved = root.resolve(relative).normalize()
        require(resolved.startsWith(root)) { "Showcase source path escapes source root: $relativePath" }
        var segment = root
        val parts = root.relativize(resolved).toList()
        parts.dropLast(1).forEach { part ->
            segment = segment.resolve(part)
            require(Files.isSymbolicLink(segment).not()) { "Showcase source intermediate is symbolic: $segment" }
            require(Files.isDirectory(segment, LinkOption.NOFOLLOW_LINKS)) { "Showcase source intermediate is not a directory: $segment" }
            ShowcasePaths.requireSafeSegments(segment, "Showcase source intermediate")
        }
        ShowcasePaths.requireSafeSegments(resolved, "Showcase source file")
        require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) {
            "Showcase source file is missing or not regular: $resolved"
        }
        require(Files.isSymbolicLink(resolved).not()) { "Showcase source file is symbolic: $resolved" }
        return resolved
    }
}
