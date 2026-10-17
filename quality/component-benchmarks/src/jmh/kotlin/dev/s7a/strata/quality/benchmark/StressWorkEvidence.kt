package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceSelection
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
        println("stress,idleCommands,updatedCommands,subscriptions,diagnostics")
        RuntimeSurfaceInventoryEvidence.verify()
        val selection = PerformanceSelection(StressWorkload.entries.map { it.name }.toSet(), System.getProperty("strata.performance.workloads"))
        StressWorkload.entries.filter { it.name in selection.ids }.forEach(::verify)
    }

    private fun verify(workload: StressWorkload) {
        val state = StressRenderingBenchmark.StressSession()
        state.workload = workload
        state.setup()
        try {
            state.monitorWork()
            val initial = state.idle()
            state.verifyNineSlicePixels(initial)

            check(state.idle() === initial)
            val before = state.subscriptions
            val updated = state.update()
            state.verifyNineSlicePixels(updated)
            check(updated !== initial) { "The changed operation must commit a new frame" }
            if (workload == StressWorkload.Checkbox) check(state.checked) { "Actual pointer input did not toggle the checkbox" }
            state.lifecycle()
            check(state.subscriptions == before) { "An independent lifetime retained subscriptions" }
            println("${workload.name},${initial.drawCommands.size},${updated.drawCommands.size},${state.subscriptions},${state.diagnostics}")
        } finally {
            state.close()
        }
        check(state.subscriptions == 0) { "Terminal cleanup retained source observers" }
    }
}
