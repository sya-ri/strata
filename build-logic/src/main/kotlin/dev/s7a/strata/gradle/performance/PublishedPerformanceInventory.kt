package dev.s7a.strata.gradle.performance

/**
 * Connects the actual publication model to reviewed executable performance fixture registrations.
 * Each UTF-8 TSV row names a project, physical host, repository-relative fixture file and verification task.
 * This checks registration and entry-point existence; it neither runs a meter nor certifies evidence.
 */
public object PublishedPerformanceInventory {
    /**
     * Rejects omitted/new modules, duplicate hosts, invalid source paths and missing executable gates.
     * Callbacks inspect the current repository and Gradle model, without starting measurement tasks.
     */
    public fun verify(
        publishedProjects: Set<String>,
        lines: List<String>,
        sourceExists: (String) -> Boolean,
        taskExists: (String) -> Boolean,
    ): Int {
        require(publishedProjects.isNotEmpty())
        val rows =
            lines.filter { it.isNotBlank() && it.startsWith('#').not() }.map { line ->
                line.split('\t').also { require(it.size == 4 && it.all(String::isNotBlank)) { "Expected project, host, fixture and verification task: $line" } }
            }
        val registered = rows.map { it[0] }.toSet()
        require(registered == publishedProjects) {
            "Published performance registration changed: missing=${publishedProjects - registered}, stale=${registered - publishedProjects}"
        }
        val hosts = rows.map { it[0] to PerformanceModuleHost.valueOf(it[1]) }
        require(hosts.distinct().size == hosts.size) { "Duplicate published module/host registration" }
        rows.forEach { row ->
            val source = row[2]
            require('\\' !in source && ':' !in source && source.none(Char::isISOControl) && source.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") }) { "Unsafe performance fixture path: $source" }
            require(sourceExists(source)) { "Missing performance fixture: $source" }
            val gate = row[3]
            require(gate.startsWith(':') && gate.split(':').drop(1).all { it.isNotBlank() && it.none(Char::isISOControl) && '/' !in it && '\\' !in it }) { "Invalid performance verification task: $gate" }
            require(taskExists(gate)) { "Missing performance verification task: $gate" }
        }
        return rows.size
    }
}
