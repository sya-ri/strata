package dev.s7a.strata.quality.benchmark

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
                val work = state.workSnapshot()
                check(work.overflowed.not())
                check(work.counts.getValue(UiRenderMetric.FrameSuccess) == 1L)
                val expected =
                    when (workload) {
                        ReactiveWorkload.Static, ReactiveWorkload.MapEqual -> 0L
                        ReactiveWorkload.Nested -> 2L
                        ReactiveWorkload.FanOut128 -> 128L
                        else -> 1L
                    }
                check(work.counts.getValue(UiRenderMetric.ContentEvaluation) == expected) { "Unexpected evaluation count for $workload: $work" }
                if (expected == 0L) {
                    listOf(UiRenderMetric.NodeUpdate, UiRenderMetric.Measure, UiRenderMetric.Layout, UiRenderMetric.Paint).forEach { metric ->
                        check(work.counts.getValue(metric) == 0L)
                    }
                }
                if (workload == ReactiveWorkload.Independent128) check(work.counts.getValue(UiRenderMetric.ConsumerNotification) == 1L)
                check(work.counts.getValue(UiRenderMetric.RowEvaluation) <= 7L)
                check(work.nodes.size < 400)
                val counts =
                    listOf(
                        UiRenderMetric.ContentEvaluation,
                        UiRenderMetric.NodeUpdate,
                        UiRenderMetric.Measure,
                        UiRenderMetric.Layout,
                        UiRenderMetric.Paint,
                        UiRenderMetric.RowEvaluation,
                    ).map { work.counts.getValue(it) }
                println(
                    (
                        listOf(workload.name) + counts +
                            listOf(
                                work.nodes.size,
                                work.activeSubscriptions,
                                work.counts.getValue(UiRenderMetric.ConsumerNotification),
                            )
                    ).joinToString(","),
                )
            } finally {
                state.close()
            }
        }
    }
}
