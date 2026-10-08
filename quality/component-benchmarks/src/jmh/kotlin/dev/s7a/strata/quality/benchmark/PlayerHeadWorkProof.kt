package dev.s7a.strata.quality.benchmark

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.performance.JmhWorkloadInventory
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import java.util.function.IntConsumer
import java.util.function.LongSupplier

/**
 * Untimed actual-read and immutable-image construction proof for every fixed operation/workload pair.
 * Baseline eager hidden preparation is reported explicitly; it is not relabeled as candidate lazy behavior.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object PlayerHeadWorkProof {
    /**
     * Verifies the full generated inventory, exact pixels, current retained bounds, reuse and terminal release.
     * The callback counter is active only around the actual operation, never around independent pixel reads.
     */
    internal fun verify(
        begin: IntConsumer,
        reads: LongSupplier,
        images: LongSupplier,
        stop: Runnable,
    ) {
        check(JmhWorkloadInventory.capture(listOf(PlayerHeadLayerBenchmark::class.java), setOf("avgt")).size == PlayerHeadWorkload.entries.size * Operation.entries.size)
        println("playerHeadWorkload,operation,sourceReads,constructedLayers,currentRetainedPixels,terminalRetainedPixels,nativeUploads,gpuWork")
        val counters = Counters(begin, reads, images, stop)
        PlayerHeadWorkload.entries.forEach { workload ->
            val assets = PlayerHeadFixture.Assets()
            Operation.entries.forEach { operation -> verify(workload, assets, operation, counters) }
            if (workload in setOf(PlayerHeadWorkload.SyncOne127, PlayerHeadWorkload.AsyncOne127)) verifyNearLimit(counters, assets, workload)
        }
    }

    private fun verify(
        workload: PlayerHeadWorkload,
        assets: PlayerHeadFixture.Assets,
        operation: Operation,
        counters: Counters,
    ) {
        PlayerHeadFixture(workload, assets, operation == Operation.ColdVisible || operation.preparedVisible).use { fixture ->
            fixture.attach()
            val caches = fixture.caches()
            val primed = if (operation.preparedVisible || operation == Operation.EnableHat) fixture.frame().also(fixture::verify) else null
            val previous = Previous(caches.map { it.face }, caches.map { it.hat }, primed, primed?.drawCommands?.filterIsInstance<DrawCommand.SampledImage>()?.map { it.image.copyArgb() })
            val area = (if (operation == Operation.ReplaceSize) workload.replacementSize else workload.size).let { it * it }
            counters.begin.accept(area)
            val result =
                try {
                    execute(fixture, assets, operation, caches)
                } finally {
                    counters.stop.run()
                }
            val sourceReads = counters.reads.asLong
            val constructions = counters.images.asLong
            verifyWorkCounts(workload, operation, area, sourceReads, constructions)
            verifyOwnership(fixture, result, previous, operation, area)
            val retained = result.caches.sumOf(PlayerHeadFixture.Cache::retainedPixels)
            fixture.close()
            caches.forEach(PlayerHeadFixture.Cache::verifyEmpty)
            check(fixture.activeBindings == 0)
            println(workload.name + "," + operation.method + "," + sourceReads + "," + constructions + "," + retained + ",0,N/A,N/A")
        }
    }

    private fun verifyNearLimit(
        counters: Counters,
        assets: PlayerHeadFixture.Assets,
        workload: PlayerHeadWorkload,
    ) {
        val size = 1023
        val area = size * size
        PlayerHeadFixture(workload, assets, false).use { fixture ->
            fixture.attach()
            fixture.resizeTo(size)
            val caches = fixture.caches()
            counters.begin.accept(area)
            val hidden =
                try {
                    fixture.frame(IntSize(size, size))
                } finally {
                    counters.stop.run()
                }
            verifyWorkCounts(workload, Operation.ColdHidden, area, counters.reads.asLong, counters.images.asLong)
            fixture.verify(hidden)
            val face = caches.single().face
            counters.begin.accept(area)
            fixture.hat(true)
            val visible =
                try {
                    fixture.frame(IntSize(size, size))
                } finally {
                    counters.stop.run()
                }
            verifyWorkCounts(workload, Operation.EnableHat, area, counters.reads.asLong, counters.images.asLong)
            fixture.verify(visible)
            check(caches.single().face === face)
            check(caches.single().retainedPixels() == area * 2L)
            fixture.close()
            caches.single().verifyEmpty()
            check(fixture.activeBindings == 0)
            println("playerHeadBoundary," + workload.name + ",1023,passed,0,N/A,N/A")
        }
    }

    private fun execute(
        fixture: PlayerHeadFixture,
        assets: PlayerHeadFixture.Assets,
        operation: Operation,
        caches: List<PlayerHeadFixture.Cache>,
    ): Result {
        if (operation.lifetime) {
            val fresh = PlayerHeadFixture(fixture.workload, assets, operation == Operation.VisibleLifetime)
            var freshCaches = emptyList<PlayerHeadFixture.Cache>()
            val frame =
                fresh.use {
                    it.attach()
                    freshCaches = it.caches()
                    it.frame()
                }
            return Result(frame, fresh, freshCaches)
        }
        val frame =
            when (operation) {
                Operation.ColdHidden, Operation.ColdVisible, Operation.CleanFrame -> fixture.frame()
                Operation.ReplaceHiddenSkin, Operation.ReplaceVisibleSkin -> {
                    fixture.replace(operation == Operation.ReplaceVisibleSkin)
                    fixture.frame()
                }
                Operation.ReplaceSize -> {
                    fixture.resize()
                    fixture.frame()
                }
                Operation.EnableHat -> {
                    fixture.hat(true)
                    fixture.frame()
                }
                Operation.ToggleCycle -> {
                    fixture.hat(false)
                    fixture.frame()
                    fixture.hat(true)
                    fixture.frame()
                    fixture.hat(false)
                    fixture.frame()
                }
                Operation.PreparedDirtyRepaint -> fixture.dirty()
                Operation.HiddenLifetime, Operation.VisibleLifetime -> error("Lifetime handled before retained operations.")
            }
        return Result(frame, fixture, caches)
    }

    private fun verifyWorkCounts(
        workload: PlayerHeadWorkload,
        operation: Operation,
        area: Int,
        reads: Long,
        constructions: Long,
    ) {
        check(reads == constructions * area * 4L) { "Actual reads disagree with actual completed layer construction." }
        check(constructions % workload.count == 0L)
        val expected =
            if (workload.size % 8 == 0) {
                setOf(0L)
            } else {
                when (operation) {
                    Operation.ColdHidden, Operation.ReplaceHiddenSkin, Operation.HiddenLifetime -> setOf(1L, 2L)
                    Operation.ColdVisible, Operation.ReplaceVisibleSkin, Operation.ReplaceSize, Operation.VisibleLifetime -> setOf(2L)
                    Operation.EnableHat -> setOf(0L, 1L)
                    Operation.ToggleCycle, Operation.PreparedDirtyRepaint, Operation.CleanFrame -> setOf(0L)
                }
            }
        check(constructions / workload.count in expected) { "Unexpected layer count for " + workload.name + "/" + operation.method }
    }

    private fun verifyOwnership(
        fixture: PlayerHeadFixture,
        result: Result,
        previous: Previous,
        operation: Operation,
        area: Int,
    ) {
        result.owner.verify(result.frame)
        if (operation.lifetime) {
            result.caches.forEach(PlayerHeadFixture.Cache::verifyEmpty)
            check(result.owner.activeBindings == 0)
            return
        }
        val retained = result.caches.sumOf(PlayerHeadFixture.Cache::retainedPixels)
        check(retained <= 2L * fixture.workload.count * area)
        val filtered = fixture.workload.size % 8 != 0
        if (filtered.not()) check(retained == 0L)
        when (operation) {
            Operation.EnableHat, Operation.ToggleCycle, Operation.PreparedDirtyRepaint, Operation.CleanFrame ->
                result.caches.zip(previous.faces).forEach { (cache, face) -> check(cache.face === face) }
            Operation.ReplaceHiddenSkin, Operation.ReplaceVisibleSkin, Operation.ReplaceSize ->
                if (filtered) {
                    result.caches.zip(previous.faces).forEach { (cache, face) -> check(cache.face !== face) }
                    result.caches.zip(previous.hats).forEach { (cache, hat) -> check(cache.hat == null || cache.hat !== hat) }
                }
            else -> Unit
        }
        if (operation == Operation.CleanFrame) check(result.frame === previous.frame)
        val oldFrame = previous.frame
        val pixels = previous.pixels
        if (oldFrame != null && pixels != null) {
            oldFrame.drawCommands.filterIsInstance<DrawCommand.SampledImage>().zip(pixels).forEach { (command, original) ->
                check(command.image.copyArgb().contentEquals(original))
            }
        }
    }

    private data class Result(
        val frame: RuntimeUiFrame,
        val owner: PlayerHeadFixture,
        val caches: List<PlayerHeadFixture.Cache>,
    )

    private data class Previous(
        val faces: List<DrawImage?>,
        val hats: List<DrawImage?>,
        val frame: RuntimeUiFrame?,
        val pixels: List<IntArray>?,
    )

    private data class Counters(
        val begin: IntConsumer,
        val reads: LongSupplier,
        val images: LongSupplier,
        val stop: Runnable,
    )

    private enum class Operation(
        val method: String,
        val preparedVisible: Boolean,
    ) {
        ColdHidden("coldHidden", false),
        ColdVisible("coldVisible", false),
        ReplaceHiddenSkin("replaceHiddenSkin", true),
        ReplaceVisibleSkin("replaceVisibleSkin", true),
        ReplaceSize("replaceSize", true),
        EnableHat("enableHat", false),
        ToggleCycle("toggleCycle", true),
        PreparedDirtyRepaint("preparedDirtyRepaint", true),
        CleanFrame("cleanFrame", true),
        HiddenLifetime("hiddenLifetime", true),
        VisibleLifetime("visibleLifetime", true),
        ;

        val lifetime: Boolean get() = this == HiddenLifetime || this == VisibleLifetime
    }
}
