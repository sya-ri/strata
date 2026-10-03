package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Admits every supplemental exceptional editor through real retained work and terminal font ownership.
 * Native rounding and first-fitting suffix parity are checked independently by the runtime's scalar-order tests.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object ExceptionalTextWorkEvidence {
    /**
     * Requires the separate complete matrix, clean reuse, changed semantics, pixel stability and bounded ownership.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        check(JmhWorkloadInventory.capture(listOf(ExceptionalTextFieldBenchmark::class.java), setOf("avgt")).size == 18)
        check(JmhWorkloadInventory.capture(listOf(ExceptionalTextFieldBenchmark::class.java), setOf("avgt", "sample")).size == 36)
        for (workload in ExceptionalTextWorkload.entries) {
            for (saturating in listOf(false, true)) verify(workload, saturating)
        }
    }

    private fun verify(
        workload: ExceptionalTextWorkload,
        saturating: Boolean,
    ) {
        val state = ExceptionalTextFieldBenchmark.TextSession()
        state.workload = workload
        state.saturating = saturating
        state.setup()
        try {
            state.monitorWork()
            val initial = state.idle()
            check(state.idle() === initial)
            WorkExpectation(exact = mapOf(UiRenderMetric.FrameSuccess.name to 2L, UiRenderMetric.ContentEvaluation.name to 0L, UiRenderMetric.Measure.name to 0L, UiRenderMetric.Layout.name to 0L, UiRenderMetric.Paint.name to 0L)).verify(PerformanceJson.work(state.diagnostics))
            val previous = state.value
            val updated = state.update()
            check(state.value != previous)
            check(
                updated.semantics
                    .single { it.semantics.role == SemanticsRole.TextField }
                    .semantics.label == UiText.Literal(state.value),
            )
            WorkExpectation(minimum = mapOf(UiRenderMetric.Paint.name to 1L, UiRenderMetric.Semantics.name to 1L)).verify(PerformanceJson.work(state.diagnostics))
            val before = rasterizeHeadless(initial.drawCommands, initial.size).copyArgb()
            val after = rasterizeHeadless(updated.drawCommands, updated.size).copyArgb()
            check(before.contentEquals(after)) { "Equal metric changes altered exceptional editor pixels" }
            val resources = state.ownedResources
            check(resources == 2)
            state.lifecycle()
            check(state.ownedResources == resources)
            check(state.idle() === updated)
            println("Exceptional TextField $workload saturating=$saturating: clean reuse, update and independent lifetime verified")
        } finally {
            state.close()
        }
        check(state.ownedResources == 0)
    }
}
