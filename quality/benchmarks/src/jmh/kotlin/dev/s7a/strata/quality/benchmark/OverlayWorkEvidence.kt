package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Exercises overlapping layers over many updates without imposing machine-dependent timing thresholds.
 */
@OptIn(InternalStrataRuntimeApi::class)
public object OverlayWorkEvidence {
    /**
     * Accepts optional positive update, raster-frame, and viewport-width counts for a longer local soak.
     * Every complete interval verifies actual work and retention, and every raster frame verifies all pixels.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        require(args.isEmpty() || args.size == 3)
        val updates = args.getOrNull(0)?.toInt() ?: 10_000
        val rasterFrames = args.getOrNull(1)?.toInt() ?: 2
        val width = args.getOrNull(2)?.toInt() ?: 320
        require(0 < updates && 0 < rasterFrames && 0 < width)
        listOf(1, 16, 64).forEach { layers ->
            OverlayScene(width, layers, true).use { scene ->
                var maximumNodes = 0
                var maximumCommands = 0
                repeat(updates) { index ->
                    val frame = scene.nextFrame()
                    check(frame.drawCommands.size <= layers + 1) { "The overlay display list grew beyond the current layers." }
                    maximumCommands = maxOf(maximumCommands, frame.drawCommands.size)
                    if ((index + 1) % 64 == 0 || index + 1 == updates) {
                        val work = scene.snapshot()
                        val count = ((index % 64) + 1).toLong()
                        check(work.overflowed.not())
                        check(work.counts.getValue(UiRenderMetric.ObserveEvaluation) == count)
                        check(work.counts.getValue(UiRenderMetric.Paint) == count)
                        check(work.counts.getValue(UiRenderMetric.Measure) == 0L)
                        check(work.counts.getValue(UiRenderMetric.Layout) == 0L)
                        check(work.counts.getValue(UiRenderMetric.NodeCreate) == 0L)
                        check(work.counts.getValue(UiRenderMetric.NodeDispose) == 0L)
                        check(work.activeSubscriptions == 1)
                        maximumNodes = maxOf(maximumNodes, work.nodes.size)
                        check(maximumNodes <= layers * 3 + 10)
                    }
                }
                repeat(rasterFrames) {
                    val image = scene.rasterizeNext()
                    val expected = scene.expectedPixel()
                    repeat(width * 9 / 16) { y ->
                        repeat(width) { x -> check(image.argbAt(x, y) == expected) { "Overlay pixel mismatch at ($x, $y) with $layers layers" } }
                    }
                }
                println("overlay,width=$width,layers=$layers,updates=$updates,rasters=$rasterFrames,maxNodes=$maximumNodes,maxCommands=$maximumCommands,subscriptions=1,pixels=exact")
            }
        }
    }
}
