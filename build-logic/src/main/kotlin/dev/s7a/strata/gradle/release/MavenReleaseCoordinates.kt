package dev.s7a.strata.gradle.release

import java.nio.file.Path

/**
 * Resolves the tracked Maven artifact inventory for one release version.
 *
 * The tracked file deliberately omits the release version so publishing a new Strata version does not require rewriting every artifact entry.
 */
internal object MavenReleaseCoordinates {
    /**
     * Appends [releaseVersion] to each canonical `group:artifact` entry.
     *
     * @param artifactLines tracked artifact inventory in declaration order.
     * @param releaseVersion exact version shared by every resolved coordinate.
     * @return unique `group:artifact:version` coordinates in declaration order.
     * @throws IllegalStateException when the version or inventory is empty, malformed, or duplicated.
     */
    internal fun resolve(
        artifactLines: List<String>,
        releaseVersion: String,
    ): List<String> {
        check(releaseVersion.matches(RELEASE_VERSION)) { "Invalid Maven release version: $releaseVersion" }
        val artifacts = artifactLines.filter(String::isNotBlank)
        check(artifacts.isNotEmpty()) { "The Maven artifact inventory must contain at least one entry." }
        artifacts.forEach { artifact ->
            val segments = artifact.split(':')
            check(
                segments.size == ARTIFACT_SEGMENT_COUNT &&
                    segments[0].matches(GROUP_PATTERN) &&
                    segments[1].isSafePathSegment(),
            ) {
                "Invalid Maven artifact inventory entry: $artifact"
            }
        }
        check(artifacts.distinct().size == artifacts.size) { "Maven artifact inventory entries must be unique." }
        return artifacts.map { artifact -> "$artifact:$releaseVersion" }
    }

    private const val ARTIFACT_SEGMENT_COUNT = 2
    private val GROUP_PATTERN = Regex("[A-Za-z0-9_+-]+(?:\\.[A-Za-z0-9_+-]+)*")
    private val PATH_SEGMENT_PATTERN = Regex("[A-Za-z0-9_.+-]+")
    private val RELEASE_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?")
    private val SAFE_SEGMENT_ROOT = Path.of("coordinate")

    private fun String.isSafePathSegment(): Boolean = matches(PATH_SEGMENT_PATTERN) && SAFE_SEGMENT_ROOT.resolve(this).normalize().parent == SAFE_SEGMENT_ROOT
}
