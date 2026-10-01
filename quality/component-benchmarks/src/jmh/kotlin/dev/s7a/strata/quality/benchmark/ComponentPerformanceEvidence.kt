package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhPerformanceRunner
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.JvmPerformanceInputs
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
        if (System.getProperty("strata.performance.stress", "false").toBooleanStrict()) {
            stress(args)
            return
        }
        val selected = ComponentInventoryEvidence.select(ComponentInventoryEvidence.changedPaths())
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
                    mapOf("component-api" to Path.of(checkNotNull(javaClass.getResource("/component-api.tsv")).toURI())),
        )
    }

    private fun stress(args: Array<String>) {
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val benchmark = StressRenderingBenchmark::class.java
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val parameters = if (smoke) mapOf("workload" to setOf(StressWorkload.VirtualList100.name)) else emptyMap()
        val options = args.drop(2) + if (smoke) listOf("-p", "workload=${StressWorkload.VirtualList100.name}") else emptyList()
        JmhPerformanceRunner.run(
            options.toTypedArray(),
            listOf(benchmark),
            mapOf("api" to "dev.s7a.strata.component.UiScope", "core" to "dev.s7a.strata.runtime.spi.RuntimeUiSession", "minecraft" to "dev.s7a.strata.runtime.minecraft.MinecraftUiHost", "fonts" to "dev.s7a.strata.runtime.minecraft.font.lwjgl.LwjglMinecraftFontBackendFactory"),
            Path.of(args[0]),
            args[1].toInt(),
            JmhWorkloadInventory.capture(listOf(benchmark), setOf(mode.shortLabel()), parameters),
            JvmPerformanceInputs.read(Path.of(checkNotNull(System.getProperty("strata.performance.inputs")))),
        )
    }
}
