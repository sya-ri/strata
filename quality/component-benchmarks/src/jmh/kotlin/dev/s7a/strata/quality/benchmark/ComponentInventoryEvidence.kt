package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JvmApiInventory
import dev.s7a.strata.performance.PerformanceCoverage
import dev.s7a.strata.performance.PerformanceHost
import dev.s7a.strata.performance.PerformanceInventory
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.performance.PerformanceScenario
import java.nio.file.Files
import java.nio.file.Path

/**
 * Consumer-owned component assignments checked against the kit's real binary inventory and coverage gate.
 * A new overload requires a reviewed assignment; the verification task never updates its own baseline.
 */
public object ComponentInventoryEvidence {
    private val phases = setOf(PerformancePhase.Idle, PerformancePhase.Input, PerformancePhase.Resize)

    /**
     * Captures current exact assignments into an explicit staging file for review, without modifying the checked-in baseline.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        val destination = Path.of(args.single()).toAbsolutePath().normalize()
        Files.createDirectories(checkNotNull(destination.parent))
        val lines = methods().sorted().map { symbol -> "${component(symbol).name}\t$symbol" }
        Files.writeString(destination, lines.joinToString("\n", postfix = "\n"))
    }

    /**
     * Checks every exact API assignment and executable component registration before accepting any narrow selection.
     */
    public fun verify(): PerformanceCoverage {
        val registration = registration()
        registration.coverage.selectChangedPaths(registration.inventory)
        return registration.coverage
    }

    /**
     * Selects the union of all known changed owners, with full fallback for unknown paths after checking the entire API.
     */
    public fun select(changedPaths: Set<String>? = null): List<ComponentWorkload> {
        val registration = registration()
        return registration.coverage.selectChangedPaths(registration.inventory, changedPaths).map { ComponentWorkload.valueOf(it.id) }
    }

    /**
     * Reads the caller's optional UTF-8 repository-relative path list; no Git invocation or impact inference occurs here.
     */
    public fun changedPaths(): Set<String>? = System.getProperty("strata.performance.changedPaths")?.let { Files.readAllLines(Path.of(it), Charsets.UTF_8).toSet() }

    private fun registration(): ComponentPerformanceRegistration {
        val resource = checkNotNull(javaClass.getResourceAsStream("/component-api.tsv")) { "The reviewed component API performance assignments are missing" }
        val rows = resource.bufferedReader(Charsets.UTF_8).use { it.readLines() }.map { line -> line.split('\t', limit = 2).also { require(it.size == 2) } }
        val assignments = rows.associate { row -> row[1] to ComponentWorkload.valueOf(row[0]).name }
        require(rows.size == assignments.size) { "Duplicate component API assignment" }
        require(assignments.all { (symbol, feature) -> component(symbol).name == feature }) { "Component API assignment names another workload" }
        val features = ComponentWorkload.entries.map { it.name }.toSet()
        val coverage =
            PerformanceCoverage(
                features.associateWith { setOf(PerformanceHost.Jvm) },
                features.map { feature -> PerformanceScenario(feature, setOf(feature), setOf(PerformanceHost.Jvm), phases, "shipped-component-bitmap-v1") },
                features.associateWith { phases },
            )
        val surface =
            PerformanceInventory(
                mapOf("api" to methods()),
                mapOf("api" to assignments),
                sourceOwners(assignments) + mapOf("quality/component-benchmarks/" to features),
            )
        return ComponentPerformanceRegistration(coverage, surface)
    }

    private fun sourceOwners(assignments: Map<String, String>): Map<String, Set<String>> {
        val api =
            assignments.entries
                .groupBy {
                    it.key
                        .substringBefore('#')
                        .substringAfterLast('.')
                        .removeSuffix("Kt")
                }.mapKeys { (owner, _) -> "api/src/main/kotlin/dev/s7a/strata/component/$owner.kt" }
                .mapValues { (_, symbols) -> symbols.map { it.value }.toSet() }
        val showcaseSuffix = setOf(ComponentWorkload.FlowRow, ComponentWorkload.TextField, ComponentWorkload.TextArea, ComponentWorkload.Slot)
        val examples =
            ComponentWorkload.entries.associate { component ->
                val suffix = if (component in showcaseSuffix) "ShowcaseExample" else "Example"
                "integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/Minecraft${component.name}$suffix.kt" to setOf(component.name)
            }
        return api + examples
    }

    private fun methods(): Set<String> =
        JvmApiInventory.componentEntryPoints(javaClass.classLoader, mapOf("api" to "dev.s7a.strata.component.UiScope"), "dev.s7a.strata.component.UiScope").getValue("api").also { methods ->
            check(methods.map(::component).toSet() == ComponentWorkload.entries.toSet()) { "Public component performance registrations changed" }
        }

    private fun component(symbol: String): ComponentWorkload {
        val name = symbol.substringAfter("#method:").substringAfter(':').substringBefore(':')
        val aliases = mapOf("TextStringSource" to ComponentWorkload.Text, "TextUiTextSource" to ComponentWorkload.Text)
        return aliases[name] ?: ComponentWorkload.valueOf(Regex("^([A-Z][A-Za-z]+)State[0-9a-f]{12}_[1-7]$").matchEntire(name)?.groupValues?.get(1) ?: name)
    }
}
