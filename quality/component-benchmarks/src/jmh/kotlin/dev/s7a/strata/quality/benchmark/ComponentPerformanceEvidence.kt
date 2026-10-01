package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhPerformanceRunner
import org.openjdk.jmh.annotations.Benchmark
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
        val selected = ComponentInventoryEvidence.select(ComponentInventoryEvidence.changedPaths())
        if (selected.isEmpty()) return
        val smoke = System.getProperty("strata.performance.smoke", "false").toBooleanStrict()
        val components = if (smoke) selected.take(1) else selected
        val parameters = components.joinToString(",") { it.name }
        val benchmark = ComponentRenderingBenchmark::class.java
        val mode = Mode.deepValueOf(System.getProperty("strata.performance.mode", "avgt"))
        val expected =
            benchmark.declaredMethods
                .filter { it.isAnnotationPresent(Benchmark::class.java) }
                .flatMap { method ->
                    components.map { component -> JmhPerformanceRunner.workloadIdentity("${benchmark.name}.${method.name}", mode.shortLabel(), mapOf("component" to component.name)) }
                }.toSet()
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
        )
    }
}
