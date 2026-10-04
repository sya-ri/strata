package dev.s7a.strata.quality.benchmark

import com.google.gson.JsonParser
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmApiInventory
import dev.s7a.strata.performance.PerformanceCoverage
import dev.s7a.strata.performance.PerformanceHost
import dev.s7a.strata.performance.PerformanceInventory
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.performance.PerformanceScenario
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reviewed full binary-surface registration for the four modules actually loaded by the portable/native-font corpus.
 * Registration preserves every overload and owner; it does not claim that every JVM member has executed.
 */
public object RuntimeSurfaceInventoryEvidence {
    /**
     * Stages a prospective exact registration for review without changing the checked-in verification baseline.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.size == 1)
        val destination = Path.of(args.single()).toAbsolutePath().normalize()
        Files.createDirectories(checkNotNull(destination.parent))
        val rows =
            surfaces().flatMap { (module, symbols) ->
                val feature = RuntimeSurfaceFeature.entries.single { it.module.contentEquals(module) }
                symbols.sorted().map { symbol -> "$module\t${feature.name}\t$symbol" }
            }
        Files.writeString(destination, rows.joinToString("\n", postfix = "\n"))
    }

    /**
     * Rejects new, removed and unassigned members before either full or narrow component collection.
     */
    public fun verify() {
        val resource = checkNotNull(javaClass.getResourceAsStream("/runtime-api.tsv")) { "The reviewed portable runtime API performance registration is missing" }
        val rows = resource.bufferedReader(Charsets.UTF_8).use { it.readLines() }.map { line -> line.split('\t').also { require(it.size == 3) } }
        rows.forEach { row -> require(RuntimeSurfaceFeature.valueOf(row[1]).module.contentEquals(row[0])) { "Runtime API assignment names another module" } }
        val assignments = rows.groupBy { it[0] }.mapValues { (_, members) -> members.associate { row -> row[2] to RuntimeSurfaceFeature.valueOf(row[1]).name } }
        check(assignments.values.sumOf { it.size } == rows.size) { "Duplicate runtime API performance registration" }
        val inventory = PerformanceInventory(surfaces(), assignments, RuntimeSurfaceFeature.entries.associate { it.sourceOwner to setOf(it.name) })
        val scenarioFeatures = registeredScenarios()
        val features = RuntimeSurfaceFeature.entries.map { it.name }.toSet()
        val coverage = PerformanceCoverage(features.associateWith { setOf(PerformanceHost.Jvm) }, scenarioFeatures, RuntimeSurfaceFeature.entries.associate { feature -> feature.name to if (feature == RuntimeSurfaceFeature.Fonts) setOf(PerformancePhase.Prepare, PerformancePhase.Idle, PerformancePhase.Update, PerformancePhase.Release) else setOf(PerformancePhase.Idle, PerformancePhase.Update, PerformancePhase.Input, PerformancePhase.Resize, PerformancePhase.Release) })
        coverage.selectChangedPaths(inventory)
        println("Verified ${rows.size} exact portable runtime API symbols in ${assignments.size} loaded modules")
    }

    private fun surfaces(): Map<String, Set<String>> = JvmApiInventory.capture(javaClass.classLoader, RuntimeSurfaceFeature.entries.associate { it.module to it.representative })

    private fun phase(identity: String): PerformancePhase {
        val method =
            JsonParser
                .parseString(identity)
                .asJsonArray[0]
                .asString
                .substringAfterLast('.')
        val phases = mapOf("idle" to PerformancePhase.Idle, "warm" to PerformancePhase.Idle, "update" to PerformancePhase.Update, "churn" to PerformancePhase.Update, "visualGlyphs" to PerformancePhase.Update, "pointer" to PerformancePhase.Input, "resize" to PerformancePhase.Resize, "lifecycle" to PerformancePhase.Release, "load" to PerformancePhase.Prepare)
        return requireNotNull(phases[method]) { "Register the operation phase of the generated benchmark: $method" }
    }

    private fun registeredScenarios(): List<PerformanceScenario> {
        val components = JmhWorkloadInventory.capture(listOf(ComponentRenderingBenchmark::class.java, StressRenderingBenchmark::class.java, ExceptionalTextFieldBenchmark::class.java), setOf("avgt"))
        val fonts = JmhWorkloadInventory.capture(listOf(FontProviderBenchmark::class.java, FontTextBenchmark::class.java), setOf("avgt"))
        return components.map { id -> PerformanceScenario(id, setOf(RuntimeSurfaceFeature.Api.name, RuntimeSurfaceFeature.Core.name, RuntimeSurfaceFeature.Minecraft.name), setOf(PerformanceHost.Jvm), setOf(phase(id)), "portable-rendering-v1") } +
            fonts.map { id -> PerformanceScenario(id, setOf(RuntimeSurfaceFeature.Fonts.name), setOf(PerformanceHost.Jvm), setOf(phase(id)), "portable-fonts-v1") }
    }
}
