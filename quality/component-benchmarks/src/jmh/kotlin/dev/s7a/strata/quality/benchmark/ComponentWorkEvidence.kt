package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceHost
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.PerformancePhase
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Exercises every compiled component workload with shared deterministic assertions before accepting JMH evidence.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object ComponentWorkEvidence {
    /**
     * Verifies clean work reuse, actual input/resize paths and independent terminal lifetimes without timing thresholds.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        verifyJmhInventory()
        val coverage = ComponentInventoryEvidence.verify()
        val selected = ComponentInventoryEvidence.select(ComponentInventoryEvidence.changedPaths())
        check(ComponentInventoryEvidence.select(setOf("api/src/main/kotlin/dev/s7a/strata/component/CanvasComponents.kt", "api/src/main/kotlin/dev/s7a/strata/component/TiledImageComponents.kt")).toSet() == setOf(ComponentWorkload.Canvas, ComponentWorkload.TiledImage))
        check(ComponentInventoryEvidence.select(setOf("unregistered/New.kt")).toSet() == ComponentWorkload.entries.toSet())
        val completed = mutableSetOf<Triple<String, PerformanceHost, PerformancePhase>>()
        println("component,idleCommands,nodes,subscriptions")
        selected.forEach { component ->
            val state = ComponentRenderingBenchmark.ComponentSession()
            state.component = component
            state.setup()
            try {
                state.monitorWork()
                val clean = state.idle()
                val work = PerformanceJson.work(state.snapshot())
                WorkExpectation(
                    exact =
                        mapOf(
                            UiRenderMetric.FrameSuccess.name to 1L,
                            UiRenderMetric.ContentEvaluation.name to 0L,
                            UiRenderMetric.Measure.name to 0L,
                            UiRenderMetric.Layout.name to 0L,
                            UiRenderMetric.Paint.name to 0L,
                        ),
                ).verify(work)
                completed.add(Triple(component.name, PerformanceHost.Jvm, PerformancePhase.Idle))
                state.pointer()
                completed.add(Triple(component.name, PerformanceHost.Jvm, PerformancePhase.Input))
                state.resize()
                completed.add(Triple(component.name, PerformanceHost.Jvm, PerformancePhase.Resize))
                state.lifecycle()
                println("${component.name},${clean.drawCommands.size},${work.getValue("NodeInventorySize")},${work.getValue("ActiveSubscriptions")}")
            } finally {
                state.close()
            }
        }
        coverage.verifyCompleted(completed, selected.map { it.name }.toSet())
    }

    private fun verifyJmhInventory() {
        val nativeRows = checkNotNull(javaClass.getResourceAsStream("/native-components.tsv")).bufferedReader(Charsets.UTF_8).use { it.readLines() }
        check(nativeRows.size == ComponentWorkload.entries.size + 1 && nativeRows.toSet() == ComponentWorkload.entries.map { it.name }.toSet() + "NativeCanvas") { "Review the changed native component registration" }
        val fixtures = listOf(ComponentRenderingBenchmark::class.java)
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt")).size == 4 * ComponentWorkload.entries.size)
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt", "sample")).size == 8 * ComponentWorkload.entries.size)
        check(JmhWorkloadInventory.capture(fixtures, setOf("avgt"), mapOf("component" to setOf(ComponentWorkload.Row.name))).size == 4)
        check(runCatching { JmhWorkloadInventory.capture(fixtures, setOf("avgt"), mapOf("unknown" to setOf("Row"))) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { JmhWorkloadInventory.capture(fixtures, setOf("avgt"), mapOf("component" to setOf("Unknown"))) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { JmhWorkloadInventory.capture(listOf(ComponentProfile::class.java), setOf("avgt")) }.exceptionOrNull() is IllegalArgumentException)
    }
}
