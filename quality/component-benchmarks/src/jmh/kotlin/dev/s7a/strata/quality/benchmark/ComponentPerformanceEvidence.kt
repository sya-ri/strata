package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhFixtureSelection
import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
import dev.s7a.strata.performance.PerformanceSelection
import org.openjdk.jmh.annotations.Mode
import java.nio.file.Path

/**
 * Shipped fixture and module registration; the shared kit delegates all measurement work to JMH.
 */
public object ComponentPerformanceEvidence {
    /**
     * Accepts a fresh output directory, independent repetition index and unchanged standard JMH CLI options.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(2 < args.size)
        require(listOf("fonts", "stress", "exceptionalText", "portableText").none { System.getProperty("strata.performance.$it") != null }) { "Select generated fixture classes with strata.performance.benchmarks" }
        if (System.getProperty("strata.performance.benchmarks") != null) {
            selected(args)
            return
        }
        RuntimeSurfaceInventoryEvidence.verify()
        val affected = ComponentInventoryEvidence.select(ComponentInventoryEvidence.changedPaths())
        val requested = System.getProperty("strata.performance.workloads")
        val selection = PerformanceSelection(ComponentWorkload.entries.map { it.name }.toSet(), requested)
        val selected = affected.filter { it.name in selection.ids }
        require(requested == null || selected.isNotEmpty()) { "Explicit workload selection has no changed-path intersection" }
        if (selected.isEmpty()) return
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val components = if (smoke) selected.take(1) else selected
        val parameters = components.joinToString(",") { it.name }
        val benchmark = ComponentRenderingBenchmark::class.java
        val includes = JmhFixtureSelection.includes(listOf(benchmark))
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(listOf(benchmark), setOf(mode.shortLabel()), mapOf("component" to components.map { it.name }.toSet()))
        JmhFixtureSelection.verifyIncludes(expected, includes)
        JmhPerformanceRunner.run(
            (includes + args.drop(3) + listOf("-p", "component=$parameters")).toTypedArray(),
            listOf(ComponentRenderingBenchmark::class.java),
            mapOf(
                "api" to "dev.s7a.strata.component.UiScope",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            inputs =
                JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) +
                    mapOf("component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()), "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI())),
        )
    }

    private fun selected(args: Array<String>) {
        require(System.getProperty("strata.performance.smoke", "false").toBooleanStrict().not()) { "Smoke and explicit fixture selection are separate scopes" }
        val registered = JmhFixtureSelection.select(listOf(ComponentRenderingBenchmark::class.java))
        val methods = JmhFixtureSelection.methods(registered)
        val fixtures = registered.filter { fixture -> methods.any { it.substringBeforeLast('.') == fixture.name } }
        val includes = methods.map { "^${Regex.escape(it)}$" }
        val parameters = JmhFixtureSelection.parameters()
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(fixtures, setOf(mode.shortLabel()), parameters, includes)
        JmhFixtureSelection.verifyIncludes(expected, includes)
        val requiredInputs =
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) +
                mapOf(
                    "cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))),
                    "component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI()),
                    "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI()),
                )
        val additionalInputs = JmhFixtureSelection.inputs()
        require(requiredInputs.keys.intersect(additionalInputs.keys).isEmpty()) { "Fixture input labels overlap required control inputs" }
        RuntimeSurfaceInventoryEvidence.verify()
        JmhFixtureSelection.verifyWork(fixtures)
        JmhPerformanceRunner.run(
            (includes + args.drop(3) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }).toTypedArray(),
            fixtures,
            mapOf(
                "api" to "dev.s7a.strata.component.UiScope",
                "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession",
                "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage",
                "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost",
                "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory",
            ),
            Path.of(args[0]),
            args[1].toInt(),
            expected,
            requiredInputs + additionalInputs,
        )
    }
}
