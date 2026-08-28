package dev.s7a.strata.integration.docs

import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Validated headless launcher paths, explicit read-only assets, and component class directories.
 *
 * Construction rejects malformed, missing, or symlinked inputs and duplicate component class directories before rendering starts.
 */
internal class ShowcaseLaunchArguments private constructor(
    internal val projectRoot: Path,
    internal val stagingRoot: Path,
    internal val inputs: ShowcaseInputFiles,
    internal val componentClassDirectories: List<Path>,
) {
    /**
     * Factory for validating task launcher arguments.
     */
    companion object {
        /**
         * Parses and validates one task-specific launcher invocation.
         *
         * @param args project root, module build root, exact staging root, client archive, asset index, asset objects directory, version manifest, native inventory PNG, native inventory receipt, and one or more component class directories, in that order.
         * @param kind typed task staging kind expected by this launcher.
         * @return validated immutable launcher arguments.
         * @throws IllegalArgumentException when any path or containment invariant fails.
         */
        internal fun parse(
            args: Array<String>,
            kind: ShowcaseStagingKind,
        ): ShowcaseLaunchArguments {
            require(10 <= args.size) {
                "Showcase launcher requires three output roots, four explicit game asset paths, two native inventory evidence files, and a component class directory."
            }
            val projectRoot = parsePath(args[0], "repository root")
            ShowcasePaths.requireDirectory(projectRoot, "repository root")
            val stagingRoot = stagingRoot(projectRoot, args[1], args[2], kind)
            val inputs =
                ShowcaseInputFiles(
                    parsePath(args[3], "Minecraft client archive"),
                    parsePath(args[4], "Minecraft asset index"),
                    parsePath(args[5], "Minecraft asset objects"),
                    parsePath(args[6], "Minecraft version manifest"),
                    parsePath(args[7], "native inventory image"),
                    parsePath(args[8], "native inventory receipt"),
                )
            inputs.requireOutside(
                listOf(stagingRoot, projectRoot.resolve("docs/components"), projectRoot.resolve("docs/components.md"), projectRoot.resolve("README.md")),
            )
            val classDirectories = args.drop(9).map { value -> parsePath(value, "component class directory") }
            require(classDirectories.toSet().size == classDirectories.size) { "Component class directories must be unique after normalization." }
            classDirectories.forEach { directory -> ShowcasePaths.requireDirectory(directory, "component class directory") }
            return ShowcaseLaunchArguments(projectRoot, stagingRoot, inputs, classDirectories)
        }

        private fun stagingRoot(
            projectRoot: Path,
            moduleBuild: String,
            staging: String,
            kind: ShowcaseStagingKind,
        ): Path {
            val moduleBuildRoot = parsePath(moduleBuild, "module build root")
            val stagingRoot = parsePath(staging, "staging root")
            val expectedBuild = projectRoot.resolve("integration/docs/build").toAbsolutePath().normalize()
            require(moduleBuildRoot == expectedBuild) {
                "Module build root must be exactly $expectedBuild but was $moduleBuildRoot."
            }
            require(moduleBuildRoot != projectRoot && moduleBuildRoot.startsWith(projectRoot)) {
                "Module build root must be a distinct repository descendant."
            }
            ShowcasePaths.requireSafeSegments(moduleBuildRoot, "module build root")
            val expectedStaging = moduleBuildRoot.resolve("component-showcase").resolve(kind.directoryName).normalize()
            require(stagingRoot == expectedStaging) {
                "Staging root must be exactly $expectedStaging but was $stagingRoot."
            }
            ShowcasePaths.requireSafeSegments(stagingRoot, "staging root")
            return stagingRoot
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
    }
}
