package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.CompilerApiInventory
import dev.s7a.strata.performance.PerformanceCoverage
import dev.s7a.strata.performance.PerformanceInventory
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.performance.PerformanceScenario
import java.nio.file.Files
import java.nio.file.Path

/**
 * Connects every published compiler member to its reviewed physical-host fixture registration.
 * The root publication-model gate and compiler checkKotlinAbi tasks must precede this adapter.
 * Registration does not establish timing evidence or prove execution of every member.
 */
internal object PublishedHostInventoryEvidence {
    /**
     * Verifies the checked-in assignments; never updates its own baseline.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1)
        val root = Path.of(args.single()).toAbsolutePath().normalize()
        val registrations = registrations(root)
        val surfaces = registrations.associate { row -> identity(row) to symbols(root, row) }
        require(surfaces.size == registrations.size) { "Duplicate published module/host registration" }
        val resource = checkNotNull(javaClass.getResourceAsStream("/published-host-api.tsv")) { "Reviewed published host/member assignments are missing" }
        val rows = resource.bufferedReader(Charsets.UTF_8).use { it.readLines() }.map { line -> line.split('\t').also { require(it.size == 3 && it.all(String::isNotBlank)) } }
        val assignments = rows.groupBy { it[0] }.mapValues { (_, members) -> members.associate { it[2] to it[1] } }
        require(assignments.values.sumOf { it.size } == rows.size) { "Duplicate published member assignment" }
        val hosts = registrations.associate { identity(it) to RegisteredPerformanceHost.valueOf(it[1]).boundary }
        rows.forEach { row -> require(hosts.getValue(row[0]) == hosts.getValue(row[1])) { "Published API member assigned across physical hosts" } }
        val owners = registrations.groupBy { modulePath(it[0]) + "/" }.mapValues { (_, members) -> members.map(::identity).toSet() }
        val coverage =
            PerformanceCoverage(
                hosts.mapValues { setOf(it.value) },
                hosts.map { (feature, host) -> PerformanceScenario(feature, setOf(feature), setOf(host), setOf(PerformancePhase.Prepare), "published-fixture-registration-v1") },
                hosts.keys.associateWith { setOf(PerformancePhase.Prepare) },
            )
        coverage.selectChangedPaths(PerformanceInventory(surfaces, assignments, owners))
        println("Verified ${rows.size} compiler API member/host assignments in ${surfaces.size} publication registrations; formal host evidence is separate.")
    }

    /**
     * Stages actual compiler declarations for review without replacing the checked-in assignments.
     */
    internal fun capture(
        root: Path,
        output: Path,
    ) {
        val rows = registrations(root).flatMap { row -> symbols(root, row).sorted().map { symbol -> "${identity(row)}\t${identity(row)}\t$symbol" } }
        Files.createDirectories(checkNotNull(output.parent))
        require(Files.exists(output).not()) { "Prospective inventory already exists" }
        Files.writeString(output, rows.joinToString("\n", postfix = "\n"))
    }

    private fun registrations(root: Path): List<List<String>> =
        Files
            .readAllLines(root.resolve("gradle/performance-modules.tsv"), Charsets.UTF_8)
            .filter { it.isNotBlank() && it.startsWith('#').not() }
            .map { line -> line.split('\t').also { require(it.size == 4 && it.all(String::isNotBlank)) } }

    private fun identity(row: List<String>): String = "${row[0]}@${RegisteredPerformanceHost.valueOf(row[1]).name}"

    private fun modulePath(module: String): String {
        require(module.startsWith(':') && module.split(':').drop(1).all { it.isNotBlank() && it.none(Char::isISOControl) && '/' !in it && '\\' !in it && it !in setOf(".", "..") })
        return module.removePrefix(":").replace(':', '/')
    }

    private fun symbols(
        root: Path,
        row: List<String>,
    ): Set<String> {
        val host = RegisteredPerformanceHost.valueOf(row[1])
        val directory = root.resolve(modulePath(row[0])).resolve("api").normalize()
        require(directory.startsWith(root) && Files.isDirectory(directory)) { "Published compiler ABI directory is missing: $directory" }
        val files =
            Files.list(directory).use { entries ->
                entries
                    .filter { file ->
                        val name = file.fileName.toString()
                        Files.isRegularFile(file) && name.endsWith(".api") && if (name.endsWith(".klib.api")) host.klib else host.jvm
                    }.sorted()
                    .toList()
            }
        require(files.isNotEmpty()) { "Published host compiler ABI is missing: ${identity(row)}" }
        return CompilerApiInventory.capture(mapOf(identity(row) to files)).getValue(identity(row))
    }
}
