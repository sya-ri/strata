package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Deterministic admission for actual stress operations, independent of timing or absolute latency thresholds.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object StressWorkEvidence {
    /**
     * Requires the complete generated matrix, idle reuse, actual changed work, bounded rows and terminal release.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        check(JmhWorkloadInventory.capture(listOf(StressRenderingBenchmark::class.java), setOf("avgt")).size == 3 * StressWorkload.entries.size)
        println("stress,idleCommands,updatedCommands,nodes,subscriptions")
        StressWorkload.entries.forEach(::verify)
    }

    private fun verify(workload: StressWorkload) {
        val state = StressRenderingBenchmark.StressSession()
        state.workload = workload
        state.setup()
        try {
            state.monitorWork()
            val initial = state.idle()
            state.verifyNineSlicePixels(initial)
            WorkExpectation(exact = mapOf(UiRenderMetric.FrameSuccess.name to 1L, UiRenderMetric.ContentEvaluation.name to 0L, UiRenderMetric.Measure.name to 0L, UiRenderMetric.Layout.name to 0L, UiRenderMetric.Paint.name to 0L)).verify(PerformanceJson.work(state.diagnostics))
            val before = state.subscriptions
            val updated = state.update()
            state.verifyNineSlicePixels(updated)
            val work = PerformanceJson.work(state.diagnostics)
            val required = if (workload in setOf(StressWorkload.FanOut128, StressWorkload.FanOut4096)) UiRenderMetric.ContentEvaluation else UiRenderMetric.Paint
            WorkExpectation(exact = mapOf(UiRenderMetric.FrameSuccess.name to 2L), minimum = mapOf(required.name to 1L)).verify(work)
            if (workload in setOf(StressWorkload.VirtualList100, StressWorkload.VirtualList1000000)) {
                WorkExpectation(maximum = mapOf("NodeInventorySize" to 100L)).verify(work)
            }
            if (workload == StressWorkload.Checkbox) check(state.checked) { "Actual pointer input did not toggle the checkbox" }
            state.lifecycle()
            check(state.subscriptions == before) { "An independent lifetime retained subscriptions" }
            println("${workload.name},${initial.drawCommands.size},${updated.drawCommands.size},${work.getValue("NodeInventorySize")},${state.subscriptions}")
        } finally {
            state.close()
        }
        check(state.subscriptions == 0) { "Terminal cleanup retained source observers" }
    }
}
