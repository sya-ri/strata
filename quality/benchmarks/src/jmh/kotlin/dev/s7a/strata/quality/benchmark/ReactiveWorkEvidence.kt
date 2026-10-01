package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Deterministic counterpart of the timed fixtures, recording actual evaluations and retained visible-scene nodes.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object ReactiveWorkEvidence {
    /**
     * Checks every benchmark scene without time thresholds and writes bounded CSV evidence to standard output.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty())
        println("scenario,content,nodeUpdates,measure,layout,paint,rows,nodes,subscriptions,consumers")
        ReactiveWorkload.entries.forEach { workload ->
            val state = ReactiveRenderingBenchmark.ReactiveSession()
            state.scenario = workload
            state.monitoring = true
            state.setup()
            try {
                state.nextFrame()
                val work = PerformanceJson.work(state.workSnapshot())
                val expected =
                    when (workload) {
                        ReactiveWorkload.Static, ReactiveWorkload.MapEqual -> 0L
                        ReactiveWorkload.Nested -> 2L
                        ReactiveWorkload.FanOut128 -> 128L
                        else -> 1L
                    }
                val exact = mutableMapOf(UiRenderMetric.FrameSuccess.name to 1L, UiRenderMetric.ContentEvaluation.name to expected)
                if (expected == 0L) {
                    listOf(UiRenderMetric.NodeUpdate, UiRenderMetric.Measure, UiRenderMetric.Layout, UiRenderMetric.Paint).forEach { metric -> exact[metric.name] = 0L }
                }
                if (workload == ReactiveWorkload.Independent128) exact[UiRenderMetric.ConsumerNotification.name] = 1L
                WorkExpectation(exact = exact, maximum = mapOf(UiRenderMetric.RowEvaluation.name to 7L, "NodeInventorySize" to 399L)).verify(work)
                val counts =
                    listOf(
                        UiRenderMetric.ContentEvaluation,
                        UiRenderMetric.NodeUpdate,
                        UiRenderMetric.Measure,
                        UiRenderMetric.Layout,
                        UiRenderMetric.Paint,
                        UiRenderMetric.RowEvaluation,
                    ).map { work.getValue(it.name) }
                println(
                    (
                        listOf(workload.name) + counts +
                            listOf(
                                work.getValue("NodeInventorySize"),
                                work.getValue("ActiveSubscriptions"),
                                work.getValue(UiRenderMetric.ConsumerNotification.name),
                            )
                    ).joinToString(","),
                )
            } finally {
                state.close()
            }
        }
    }
}
