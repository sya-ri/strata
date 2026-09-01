package dev.s7a.strata.integration.docs

import java.nio.file.Path

/**
 * Validated inputs for public-skill generation and checking.
 *
 * @property projectRoot trusted repository root.
 * @property stagingRoot isolated build-output directory.
 * @property exampleSourceRoot API-only compiled example source root.
 * @property releaseVersion validated root project release version.
 * @property apiClassDirectories compiled API class directories.
 */
internal class StrataSkillLaunchArguments private constructor(
    internal val projectRoot: Path,
    internal val stagingRoot: Path,
    internal val exampleSourceRoot: Path,
    internal val releaseVersion: String,
    internal val apiClassDirectories: List<Path>,
) {
    /**
     * Validates process arguments before generation reads repository-owned inputs.
     */
    internal companion object {
        /**
         * Parses launcher arguments without retaining mutable caller state.
         *
         * @param args repository root, staging root, example source root, release version, and one or more API class directories.
         * @return validated immutable launch arguments.
         */
        internal fun parse(args: Array<String>): StrataSkillLaunchArguments {
            require(5 <= args.size) { "Strata skill launcher requires project root, staging root, example root, release version, and API classes." }
            val projectRoot = Path.of(args[0]).toAbsolutePath().normalize()
            val stagingRoot = Path.of(args[1]).toAbsolutePath().normalize()
            val exampleSourceRoot = Path.of(args[2]).toAbsolutePath().normalize()
            val releaseVersion = args[3]
            val apiClassDirectories = args.drop(4).map { value -> Path.of(value).toAbsolutePath().normalize() }
            ShowcasePaths.requireDirectory(projectRoot, "Strata skill project root")
            ShowcasePaths.requireDirectory(exampleSourceRoot, "Strata skill example source root")
            require(stagingRoot.startsWith(projectRoot.resolve("integration/docs/build").normalize())) {
                "Strata skill staging root must remain below integration/docs/build: $stagingRoot"
            }
            require(releaseVersion.matches(releaseVersionPattern)) {
                "Strata skill release version must be a semantic version."
            }
            require(apiClassDirectories.toSet().size == apiClassDirectories.size) { "Strata skill API class directories are duplicated." }
            apiClassDirectories.forEach { directory -> ShowcasePaths.requireDirectory(directory, "Strata skill API class directory") }
            return StrataSkillLaunchArguments(projectRoot, stagingRoot, exampleSourceRoot, releaseVersion, apiClassDirectories)
        }

        private val releaseVersionPattern = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
    }
}
