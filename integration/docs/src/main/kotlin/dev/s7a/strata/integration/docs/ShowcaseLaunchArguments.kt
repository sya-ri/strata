package dev.s7a.strata.integration.docs

import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path

/**
 * Validated launcher paths and API class directories.
 *
 * Construction rejects malformed, duplicated, missing, or symlinked paths before rendering starts.
 */
internal class ShowcaseLaunchArguments private constructor(
    internal val projectRoot: Path,
    internal val stagingRoot: Path,
    internal val parityRoot: Path,
    internal val apiClassDirectories: List<Path>,
) {
    /**
     * Factory for validating task launcher arguments.
     */
    companion object {
        /**
         * Parses and validates one task-specific launcher invocation.
         *
         * @param args five argument groups: project root, module build root, exact staging root, Minecraft parity root, and one or more API class directories.
         * @param kind typed task staging kind expected by this launcher.
         * @return validated immutable launcher arguments.
         * @throws IllegalArgumentException when any path or containment invariant fails.
         */
        internal fun parse(
            args: Array<String>,
            kind: ShowcaseStagingKind,
        ): ShowcaseLaunchArguments {
            require(5 <= args.size) {
                "Showcase launcher requires a repository root, module build root, staging directory, Minecraft parity root, and API class directory."
            }
            val projectRoot = parsePath(args[0], "repository root")
            val moduleBuildRoot = parsePath(args[1], "module build root")
            val stagingRoot = parsePath(args[2], "staging root")
            requireDirectory(projectRoot, "repository root")
            requireNotSymlinkAncestry(projectRoot, "repository root")
            val expectedBuild = projectRoot.resolve("integration/docs/build").toAbsolutePath().normalize()
            require(moduleBuildRoot == expectedBuild) {
                "Module build root must be exactly $expectedBuild but was $moduleBuildRoot."
            }
            require(moduleBuildRoot != projectRoot && moduleBuildRoot.startsWith(projectRoot)) {
                "Module build root must be a distinct repository descendant."
            }
            requireNotSymlinkAncestry(moduleBuildRoot, "module build root")
            val expectedStaging = moduleBuildRoot.resolve("component-showcase").resolve(kind.directoryName).normalize()
            require(stagingRoot == expectedStaging) {
                "Staging root must be exactly $expectedStaging but was $stagingRoot."
            }
            requireNotSymlinkAncestry(stagingRoot, "staging root")
            val parityRoot = parsePath(args[3], "Minecraft parity root")
            val expectedParity = projectRoot.resolve("integration/minecraft-fabric-26.2/build/minecraft-parity").normalize()
            require(parityRoot == expectedParity) {
                "Minecraft parity root must be exactly $expectedParity but was $parityRoot."
            }
            requireDirectory(parityRoot, "Minecraft parity root")
            requireNotSymlinkAncestry(parityRoot, "Minecraft parity root")
            val classDirectories = args.drop(4).map { value -> parsePath(value, "API class directory") }
            require(classDirectories.isNotEmpty()) { "At least one API class directory is required." }
            val normalized = classDirectories.map { directory -> directory.toAbsolutePath().normalize() }
            require(normalized.toSet().size == normalized.size) { "API class directories must be unique after normalization." }
            normalized.forEach { directory ->
                ShowcasePaths.requireSafeSegments(directory, "API class directory")
                ShowcasePaths.requireDirectory(directory, "API class directory")
            }
            return ShowcaseLaunchArguments(projectRoot, stagingRoot, parityRoot, normalized)
        }

        private fun parsePath(
            value: String,
            label: String,
        ): Path =
            try {
                Path.of(value).toAbsolutePath().normalize()
            } catch (error: InvalidPathException) {
                throw IllegalArgumentException("Malformed $label path: $value", error)
            }

        private fun requireDirectory(
            path: Path,
            label: String,
        ) {
            require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) { "$label is not a directory: $path" }
        }

        private fun requireNotSymlinkAncestry(
            path: Path,
            label: String,
        ) = ShowcasePaths.requireSafeSegments(path, label)
    }
}
