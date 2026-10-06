package dev.s7a.strata.quality.benchmark

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
        RuntimeSurfaceInventoryEvidence.verify()
        if (System.getProperty("strata.performance.portableText", "false").toBooleanStrict()) {
            portableText(args)
            return
        }
        if (System.getProperty("strata.performance.exceptionalText", "false").toBooleanStrict()) {
            exceptionalText(args)
            return
        }
        if (System.getProperty("strata.performance.fonts", "false").toBooleanStrict()) {
            fonts(args)
            return
        }
        if (System.getProperty("strata.performance.stress", "false").toBooleanStrict()) {
            stress(args)
            return
        }
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
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected = JmhWorkloadInventory.capture(listOf(benchmark), setOf(mode.shortLabel()), mapOf("component" to components.map { it.name }.toSet()))
        JmhPerformanceRunner.run(
            (args.drop(2) + listOf("-p", "component=$parameters")).toTypedArray(),
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

    private fun portableText(args: Array<String>) {
        val benchmark = PortableTextBenchmark::class.java
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        JmhPerformanceRunner.run(
            args.drop(2).toTypedArray(),
            listOf(benchmark),
            mapOf("api" to "dev.s7a.strata.component.UiScope", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "headless" to "dev.s7a.strata.runtime.headless.HeadlessImage", "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost", "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory"),
            Path.of(args[0]),
            args[1].toInt(),
            JmhWorkloadInventory.capture(listOf(benchmark), setOf(mode.shortLabel())),
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture")))),
        )
    }

    private fun fonts(args: Array<String>) {
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val benchmarks = listOf(FontProviderBenchmark::class.java, FontTextBenchmark::class.java)
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val parameters = if (smoke) mapOf("workload" to setOf(FontWorkload.BitmapCached.name, FontWorkload.StbFaces1.name, FontWorkload.FreeTypeFaces16.name, FontWorkload.ReferenceDepth129.name), "length" to setOf("32")) else emptyMap()
        val options = args.drop(2) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            benchmarks,
            mapOf("api" to "dev.s7a.strata.component.UiScope", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost", "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory"),
            Path.of(args[0]),
            args[1].toInt(),
            JmhWorkloadInventory.capture(benchmarks, setOf(mode.shortLabel()), parameters),
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("cc0-geometric-font" to Path.of(checkNotNull(System.getProperty("strata.performance.fontFixture"))), "runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI())),
        )
    }

    private fun stress(args: Array<String>) {
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val benchmark = StressRenderingBenchmark::class.java
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val selection = PerformanceSelection(StressWorkload.entries.map { it.name }.toSet(), System.getProperty("strata.performance.workloads"))
        val workloads = if (smoke) selection.ids.take(1).toSet() else selection.ids
        val parameters = mapOf("workload" to workloads)
        val options = args.drop(2) + listOf("-p", "workload=${workloads.joinToString(",")}")
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            listOf(benchmark),
            mapOf("api" to "dev.s7a.strata.component.UiScope", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost", "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory"),
            Path.of(args[0]),
            args[1].toInt(),
            JmhWorkloadInventory.capture(listOf(benchmark), setOf(mode.shortLabel()), parameters),
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI())),
        )
    }

    private fun exceptionalText(args: Array<String>) {
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val selection = PerformanceSelection(ExceptionalTextWorkload.entries.map { it.name }.toSet(), System.getProperty("strata.performance.workloads"))
        val parameters = mapOf("workload" to if (smoke) selection.ids.take(1).toSet() else selection.ids)
        val benchmark = ExceptionalTextFieldBenchmark::class.java
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        JmhPerformanceRunner.run(
            (args.drop(2) + parameters.flatMap { (name, values) -> listOf("-p", "$name=${values.sorted().joinToString(",")}") }).toTypedArray(),
            listOf(benchmark),
            mapOf("api" to "dev.s7a.strata.component.UiScope", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost"),
            Path.of(args[0]),
            args[1].toInt(),
            JmhWorkloadInventory.capture(listOf(benchmark), setOf(mode.shortLabel()), parameters),
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))) + mapOf("runtime-api" to Path.of(checkNotNull(javaClass.getResource("/runtime-api.tsv")).toURI())),
        )
    }
}
