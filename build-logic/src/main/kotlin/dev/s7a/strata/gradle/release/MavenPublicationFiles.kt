package dev.s7a.strata.gradle.release

/**
 * Validates publication-owned file suffixes, retaining the historical JVM inventory for old release fixtures.
 */
internal object MavenPublicationFiles {
    internal val legacySuffixes: List<String> = listOf(".pom", ".module", ".jar", "-sources.jar", "-javadoc.jar")

    /**
     * Resolves unique `group:artifact:suffix` entries and requires every selected coordinate exactly once as an owner.
     */
    internal fun resolve(
        lines: List<String>?,
        artifacts: List<String>,
    ): Map<String, List<String>> {
        if (lines == null) return artifacts.associateWith { legacySuffixes }
        val entries = lines.filter(String::isNotBlank)
        check(entries.isNotEmpty() && entries.distinct().size == entries.size) { "Publication file inventory is empty or duplicated." }
        val files =
            entries
                .map { line ->
                    val parts = line.split(':')
                    check(parts.size == 3) { "Invalid publication file entry: $line" }
                    val artifact = MavenReleaseCoordinates.canonicalArtifacts(listOf(parts.take(2).joinToString(":"))).single()
                    val suffix = parts[2]
                    check(suffix.matches(Regex("(?:-[A-Za-z0-9_-]+)?\\.[A-Za-z0-9]+")) && suffix.endsWith(".asc").not()) {
                        "Unsafe publication file suffix: $line"
                    }
                    artifact to suffix
                }.groupBy({ it.first }, { it.second })
        check(files.keys == artifacts.toSet()) { "Publication file owners differ from the Maven coordinate inventory." }
        files.values.forEach { suffixes ->
            check(suffixes.containsAll(listOf(".pom", ".module")) && 2 < suffixes.size) { "Publication metadata or artifacts are missing." }
        }
        return files
    }
}
