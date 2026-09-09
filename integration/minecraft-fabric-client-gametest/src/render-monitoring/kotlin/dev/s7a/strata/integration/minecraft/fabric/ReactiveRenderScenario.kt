package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.component.Text
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.padding
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.runtime.diagnostics.UiRenderSnapshot
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map

/**
 * Shared native acceptance for automatic direct updates, projected equality, and precise phase work.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object ReactiveRenderScenario {
    /**
     * Runs against actual scheduled host frames; no fixed sleep is used as evidence.
     */
    fun verify(driver: ReactiveRenderTestDriver): String {
        val source = driver.onClient { ReactiveRenderSource(1) }
        val progress = driver.onClient { ReactiveRenderSource(0.25) }
        val owner = driver.onClient { driver.open(reactiveScreen(source, progress)) }
        val outcome =
            runCatching {
                awaitStableFrame(driver)
                val monitor = driver.onClient { owner.startRenderMonitoring() }
                verifyStableFrames(driver, monitor, source)
                verifyProjectedText(driver, monitor, source)
                verifyProgress(driver, monitor, progress)
                driver.onClient { monitor.close() }
                driver.assertPixels(literalReference())
                val inputEvidence = TextInputAppearanceScenario.verify(driver)
                "stableHostFrames=100\nequalMapUiWork=0\nequalMapNativeWork=0\nchangedMapEvaluations=1\nprogressMeasure=0\nprogressLayout=0\noverlayCallbacksOnPaintOnlyLowerUpdate=0\noverlayCallbacksOnChildGeometryChange=1\noverlayComposition=clipped-translucent-and-root-opaque\nupdatedPixels=literal-headless-exact\n" + inputEvidence
            }
        val cleanup = runCatching { driver.onClient { driver.closeScreen() } }
        outcome.exceptionOrNull()?.let { failure ->
            cleanup.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
            throw failure
        }
        cleanup.getOrThrow()
        return outcome.getOrThrow()
    }

    private fun reactiveScreen(
        source: ReactiveRenderSource<Int>,
        progress: ReactiveRenderSource<Double>,
    ): ScreenDefinition {
        val label = source.map { if (it % 2 == 0) "Even" else "Odd" }
        return ScreenDefinition("Reactive render acceptance") {
            Column(
                modifier =
                    Modifier.Empty
                        .size(160, 48)
                        .background(ArgbColor(0xFF000000.toInt()))
                        .then(ReactiveRenderOverlays)
                        .padding(8),
                spacing = 4,
            ) {
                Text(label, key = ElementKey("label"))
                ProgressBar(progress, size = IntSize(144, 12), key = ElementKey("progress"))
            }
        }
    }

    private fun literalReference(): ScreenDefinition =
        ScreenDefinition("Reactive literal reference") {
            Column(
                modifier =
                    Modifier.Empty
                        .size(160, 48)
                        .background(ArgbColor(0xFF000000.toInt()))
                        .then(ReactiveRenderOverlays)
                        .padding(8),
                spacing = 4,
            ) {
                Text("Even")
                ProgressBar(0.75, size = IntSize(144, 12))
            }
        }

    private fun awaitStableFrame(driver: ReactiveRenderTestDriver) {
        driver.await { 0L < driver.work().preparations }
        var previous = driver.onClient { driver.work() }
        driver.await {
            val current = driver.work()
            val settled = previous.hostFrames < current.hostFrames && previous.preparations == current.preparations
            previous = current
            settled
        }
    }

    private fun verifyStableFrames(
        driver: ReactiveRenderTestDriver,
        monitor: UiRenderMonitor,
        source: ReactiveRenderSource<Int>,
    ) {
        val baseline = driver.onClient { driver.work() }
        driver.await { 100L <= driver.work().hostFrames - baseline.hostFrames }
        driver.onClient {
            assertNoUiWork(monitor.snapshot())
            assertNoNativeWork(baseline, driver.work())
            check(100L <= monitor.snapshot().counts.getValue(UiRenderMetric.FrameSuccess))
            check(source.subscriptions == 1)
        }
    }

    private fun verifyProjectedText(
        driver: ReactiveRenderTestDriver,
        monitor: UiRenderMonitor,
        source: ReactiveRenderSource<Int>,
    ) {
        val equal = publish(driver, monitor) { source.publish(3) }
        driver.onClient {
            val snapshot = monitor.snapshot()
            assertNoUiWork(snapshot)
            check(snapshot.counts.getValue(UiRenderMetric.Projection) == 1L)
            check(snapshot.counts.getValue(UiRenderMetric.ProjectionEqual) == 1L)
            assertNoNativeWork(equal, driver.work())
        }
        val changed = publish(driver, monitor) { source.publish(4) }
        driver.onClient {
            val snapshot = monitor.snapshot()
            check(snapshot.overflowed.not())
            check(snapshot.counts.getValue(UiRenderMetric.StateComponentEvaluation) == 1L)
            check(0L < snapshot.counts.getValue(UiRenderMetric.Paint))
            check(changed.preparations < driver.work().preparations)
            // Ancestor paint can depend on child geometry cached during measure/layout, even at fixed outer size.
            check(0L < snapshot.counts.getValue(UiRenderMetric.Measure))
            check(snapshot.counts.getValue(UiRenderMetric.OverlayPaint) == 1L)
            check(snapshot.counts.getValue(UiRenderMetric.RootOverlayPaint) == 1L)
            val progressId = monitor.findNodes(ElementKey("progress")).single()
            check(
                snapshot.nodes
                    .single { it.id == progressId }
                    .counts
                    .getValue(UiRenderMetric.ContentEvaluation) == 0L,
            )
        }
    }

    private fun verifyProgress(
        driver: ReactiveRenderTestDriver,
        monitor: UiRenderMonitor,
        progress: ReactiveRenderSource<Double>,
    ) {
        val before = publish(driver, monitor) { progress.publish(0.75) }
        driver.onClient {
            val snapshot = monitor.snapshot()
            check(snapshot.counts.getValue(UiRenderMetric.StateComponentEvaluation) == 1L)
            check(snapshot.counts.getValue(UiRenderMetric.Measure) == 0L)
            check(snapshot.counts.getValue(UiRenderMetric.Layout) == 0L)
            check(snapshot.counts.getValue(UiRenderMetric.Paint) == 1L)
            assertCachedOverlays(snapshot)
            val after = driver.work()
            check(before.preparations < after.preparations)
            check(before.rasterizations < after.rasterizations && before.uploads < after.uploads) {
                "Changed lower progress must recompose its portable layer, including the cached foreground: before=$before, after=$after"
            }
        }
    }

    private fun publish(
        driver: ReactiveRenderTestDriver,
        monitor: UiRenderMonitor,
        action: () -> Unit,
    ): ReactiveNativeWork {
        val before =
            driver.onClient {
                monitor.checkpoint()
                action()
                driver.work()
            }
        driver.await { before.hostFrames < driver.work().hostFrames && 0L < monitor.snapshot().counts.getValue(UiRenderMetric.FrameSuccess) }
        return before
    }

    private fun assertNoUiWork(snapshot: UiRenderSnapshot) {
        check(snapshot.overflowed.not())
        listOf(
            UiRenderMetric.RootEvaluation,
            UiRenderMetric.ContentEvaluation,
            UiRenderMetric.NodeUpdate,
            UiRenderMetric.Measure,
            UiRenderMetric.Layout,
            UiRenderMetric.Paint,
            UiRenderMetric.OverlayPaint,
            UiRenderMetric.RootOverlayPaint,
            UiRenderMetric.Semantics,
        ).forEach { metric ->
            check(snapshot.counts.getValue(metric) == 0L) { "Unexpected reactive UI work: $metric=${snapshot.counts.getValue(metric)}" }
        }
    }

    private fun assertCachedOverlays(snapshot: UiRenderSnapshot) {
        check(snapshot.overflowed.not())
        check(snapshot.counts.getValue(UiRenderMetric.OverlayPaint) == 0L)
        check(snapshot.counts.getValue(UiRenderMetric.RootOverlayPaint) == 0L)
    }

    private fun assertNoNativeWork(
        before: ReactiveNativeWork,
        after: ReactiveNativeWork,
    ) {
        check(before.preparations == after.preparations && before.rasterizations == after.rasterizations && before.uploads == after.uploads) {
            "Equal mapped output changed native presentation: before=$before, after=$after"
        }
    }
}
