package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.performance.PerformanceJson
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/** All four actual shipped FlowRow consumers, independently paired with the frozen original retained node. */
@OptIn(InternalStrataRuntimeApi::class)
internal object FlowRowConsumerEvidence {
    /** Untimed full ordered command/source-pixel, semantics, frame and raster parity at all three densities. */
    fun verify() {
        val original = ComponentRenderingBenchmark.ComponentSession().also {
            it.component = ComponentWorkload.FlowRow
            it.originalFlow = true
        }
        val candidate = ComponentRenderingBenchmark.ComponentSession().also { it.component = ComponentWorkload.FlowRow }
        try {
            original.setup()
            candidate.setup()
            original.monitorWork()
            candidate.monitorWork()
            compare(original.idle(), candidate.idle())
            val originalIdle = PerformanceJson.work(original.snapshot())
            val candidateIdle = PerformanceJson.work(candidate.snapshot())
            check(originalIdle == candidateIdle)
            compare(original.resize(), candidate.resize())
            compare(original.pointer(), candidate.pointer())
            compare(original.lifecycle(), candidate.lifecycle())
            check(PerformanceJson.work(original.snapshot()) == PerformanceJson.work(candidate.snapshot()))
        } finally {
            try { candidate.close() } finally { original.close() }
        }
    }

    private fun compare(expected: RuntimeUiFrame, actual: RuntimeUiFrame) {
        check(expected.size == actual.size && expected.semantics == actual.semantics)
        // The actual 320/321 viewport constrains the shipped size modifier; all four buttons fit in one row.
        val bounds = listOf(IntRect(18, 8, 90, 28), IntRect(94, 8, 150, 28), IntRect(154, 8, 246, 28), IntRect(250, 8, 302, 28))
        check(actual.semantics.filter { it.semantics.role == SemanticsRole.Button }.map { it.bounds } == bounds)
        check(expected.drawCommands.size == actual.drawCommands.size)
        expected.drawCommands.zip(actual.drawCommands).forEach { (left, right) ->
            when (left) {
                is DrawCommand.BlitImage -> {
                    check(right is DrawCommand.BlitImage && left.copy(image = right.image) == right)
                    images(left.image, right.image)
                }
                is DrawCommand.BlitImagePixels -> {
                    check(right is DrawCommand.BlitImagePixels && left.copy(image = right.image) == right)
                    images(left.image, right.image)
                }
                is DrawCommand.SampledImage -> {
                    check(right is DrawCommand.SampledImage && left.copy(image = right.image) == right)
                    images(left.image, right.image)
                }
                is DrawCommand.FillRectangle, is DrawCommand.PushClip, is DrawCommand.PushFractionalClip, DrawCommand.PopClip -> check(left == right)
                is DrawCommand.Platform -> error("The shipped portable FlowRow must not emit opaque platform commands")
            }
        }
        for (density in listOf(1, 2, 3)) {
            val reference = rasterizeHeadless(expected.drawCommands, expected.size, density)
            val candidate = rasterizeHeadless(actual.drawCommands, actual.size, density)
            check(reference.size == candidate.size && reference.copyArgb().contentEquals(candidate.copyArgb()))
        }
    }

    private fun images(expected: DrawImage, actual: DrawImage) {
        check(expected.size == actual.size && expected.copyArgb().contentEquals(actual.copyArgb()))
    }
}

