package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.performance.WorkExpectation
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText

/**
 * Untimed real-operation admission for every frozen scalar/provider/operation combination.
 * Runtime numeric controls supply independent literal geometry; this verifier observes clean reuse, invalidation,
 * complete command equality for equal-metric replacement, physical pixels and terminal resources.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object TextInkAggregationWorkEvidence {
    /**
     * Exercises the full new corpus through the generated-fixture verification hook.
     */
    public fun verify() {
        for (scalars in listOf(1, 16, 256, 4096)) {
            for (provider in TextInkAggregationBenchmark.Provider.entries) {
                for (operation in TextInkAggregationBenchmark.Operation.entries) verify(scalars, provider, operation)
            }
        }
    }

    private fun verify(
        scalars: Int,
        provider: TextInkAggregationBenchmark.Provider,
        operation: TextInkAggregationBenchmark.Operation,
    ) {
        val state = TextInkAggregationBenchmark.TextSession()
        state.scalars = scalars
        state.provider = provider
        state.operation = operation
        state.setup()
        val monitor = state.monitorWork()
        try {
            val first = state.frame()
            val before = state.currentValue
            val next = state.frame()
            val clean = operation == TextInkAggregationBenchmark.Operation.CleanDisplayNormal
            check((first === next) == clean)
            check((before == state.currentValue) == clean)
            check(first.drawCommands == next.drawCommands) { "Equal-metric replacement changed ordered commands: $scalars/$provider/$operation" }
            check(next.semantics.any { it.semantics.label == UiText.Literal(state.currentValue) || it.semantics.value == UiText.Literal(state.currentValue) })
            check(state.ownedResources == if (provider == TextInkAggregationBenchmark.Provider.Legacy) 0 else 2)
            check(state.subscriptions == if (operation == TextInkAggregationBenchmark.Operation.ChangedEditorEnabled) 0 else 1)
            // The editor frame also blits; the compatibility glyph fixture has its own eight-by-eight images.
            val hasGlyphs = next.drawCommands.any {
                when (provider) {
                    TextInkAggregationBenchmark.Provider.Legacy -> it is DrawCommand.BlitImage && it.image.size == IntSize(8, 8)
                    else -> it is DrawCommand.SampledImage
                }
            }
            check(hasGlyphs == (provider == TextInkAggregationBenchmark.Provider.SpacingOnly).not())
            if (clean) {
                WorkExpectation(exact = mapOf(UiRenderMetric.ContentEvaluation.name to 0L, UiRenderMetric.Measure.name to 0L, UiRenderMetric.Layout.name to 0L, UiRenderMetric.Paint.name to 0L)).verify(PerformanceJson.work(monitor.snapshot()))
            } else {
                WorkExpectation(minimum = mapOf(UiRenderMetric.Paint.name to 1L, UiRenderMetric.Semantics.name to 1L)).verify(PerformanceJson.work(monitor.snapshot()))
            }
            for (density in 1..3) {
                val expected = rasterizeHeadless(first.drawCommands, first.size, density).copyArgb()
                check(expected.contentEquals(rasterizeHeadless(next.drawCommands, next.size, density).copyArgb()))
            }
            state.close()
            check(next.drawCommands == first.drawCommands)
            check(rasterizeHeadless(next.drawCommands, next.size).copyArgb().contentEquals(rasterizeHeadless(first.drawCommands, first.size).copyArgb()))
        } finally {
            state.close()
        }
        check(state.ownedResources == 0 && state.subscriptions == 0)
    }
}
