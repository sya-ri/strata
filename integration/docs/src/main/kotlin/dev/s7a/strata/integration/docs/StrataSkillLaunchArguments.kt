package dev.s7a.strata.integration.docs

import java.nio.file.Path

/**
 * Validated filesystem inputs for public-skill generation and checking.
 *
 * @property projectRoot trusted repository root.
 * @property stagingRoot isolated build-output directory.
 * @property exampleSourceRoot API-only compiled example source root.
 * @property apiClassDirectories compiled API class directories.
 */
internal class StrataSkillLaunchArguments private constructor(
    internal val projectRoot: Path,
    internal val stagingRoot: Path,
    internal val exampleSourceRoot: Path,
    internal val apiClassDirectories: List<Path>,
) {
    /**
     * Validates process arguments before generation reads repository-owned inputs.
     */
    internal companion object {
        /**
         * Parses launcher arguments without retaining mutable caller state.
         *
         * @param args repository root, staging root, example source root, and one or more API class directories.
         * @return validated immutable launch arguments.
         */
        internal fun parse(args: Array<String>): StrataSkillLaunchArguments {
            require(4 <= args.size) { "Strata skill launcher requires project root, staging root, example root, and API classes." }
            val projectRoot = Path.of(args[0]).toAbsolutePath().normalize()
            val stagingRoot = Path.of(args[1]).toAbsolutePath().normalize()
            val exampleSourceRoot = Path.of(args[2]).toAbsolutePath().normalize()
            val apiClassDirectories = args.drop(3).map { value -> Path.of(value).toAbsolutePath().normalize() }
            ShowcasePaths.requireDirectory(projectRoot, "Strata skill project root")
            ShowcasePaths.requireDirectory(exampleSourceRoot, "Strata skill example source root")
            require(stagingRoot.startsWith(projectRoot.resolve("integration/docs/build").normalize())) {
                "Strata skill staging root must remain below integration/docs/build: $stagingRoot"
            }
            require(apiClassDirectories.toSet().size == apiClassDirectories.size) { "Strata skill API class directories are duplicated." }
            apiClassDirectories.forEach { directory -> ShowcasePaths.requireDirectory(directory, "Strata skill API class directory") }
            return StrataSkillLaunchArguments(projectRoot, stagingRoot, exampleSourceRoot, apiClassDirectories)
        }
    }
}
