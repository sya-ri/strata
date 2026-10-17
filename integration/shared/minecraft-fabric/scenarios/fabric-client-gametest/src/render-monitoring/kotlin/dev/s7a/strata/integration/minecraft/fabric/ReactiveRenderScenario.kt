@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

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
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.map

/**
 * Shared native acceptance for automatic direct updates, projected equality, and exact output and texture reuse.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object ReactiveRenderScenario {
    /**
     * Runs against actual scheduled host frames; no fixed sleep is used as evidence.
     */
    fun verify(driver: ReactiveRenderTestDriver): String {
        val source = driver.onClient { ReactiveRenderSource(1) }
        val progress = driver.onClient { ReactiveRenderSource(0.25) }
        driver.onClient { driver.open(reactiveScreen(source, progress)) }
        val outcome =
            runCatching {
                awaitStableFrame(driver)
                verifyStableFrames(driver, source)
                verifyProjectedText(driver, source)
                verifyProgress(driver, progress)
                driver.assertPixels(literalReference())
                val inputEvidence = TextInputAppearanceScenario.verify(driver)
                val clipEvidence = FractionalClipScenario.verify(driver)
                "stablePresentedFrames=100\nequalMapCommands=reused\nequalMapTextures=reused\nprogressPixels=literal-headless-exact\n" + inputEvidence + clipEvidence
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
        driver.await { driver.work().inputs != null }
        var previous = driver.onClient { driver.work() }
        driver.await {
            val current = driver.work()
            val settled = previous.renderExtractions < current.renderExtractions && previous.inputs === current.inputs
            previous = current
            settled
        }
    }

    private fun verifyStableFrames(
        driver: ReactiveRenderTestDriver,
        source: ReactiveRenderSource<Int>,
    ) {
        val callbacks = driver.onClient { ReactiveRenderOverlays.localCalls to ReactiveRenderOverlays.rootCalls }
        val baseline = driver.onClient { driver.work() }
        driver.await { 100L <= driver.work().renderExtractions - baseline.renderExtractions }
        driver.onClient {
            assertSamePresentation(baseline, driver.work())
            check(callbacks == (ReactiveRenderOverlays.localCalls to ReactiveRenderOverlays.rootCalls))
            check(source.subscriptions == 1)
        }
    }

    private fun verifyProjectedText(
        driver: ReactiveRenderTestDriver,
        source: ReactiveRenderSource<Int>,
    ) {
        val equal = publish(driver) { source.publish(3) }
        driver.onClient { assertSamePresentation(equal, driver.work()) }
        val changed = publish(driver) { source.publish(4) }
        driver.onClient {
            check(changed.commands !== driver.work().commands)
            check(changed.inputs !== driver.work().inputs)
        }
    }

    private fun verifyProgress(
        driver: ReactiveRenderTestDriver,
        progress: ReactiveRenderSource<Double>,
    ) {
        val callbacks = driver.onClient { ReactiveRenderOverlays.localCalls to ReactiveRenderOverlays.rootCalls }
        val before = publish(driver) { progress.publish(0.75) }
        driver.onClient {
            val after = driver.work()
            check(before.commands !== after.commands && before.inputs !== after.inputs)
            check(callbacks == (ReactiveRenderOverlays.localCalls to ReactiveRenderOverlays.rootCalls))
            check(before.samePortable(after).not()) { "Changed lower progress must recompose its complete portable layer." }
        }
    }

    private fun publish(
        driver: ReactiveRenderTestDriver,
        action: () -> Unit,
    ): ReactiveNativeWork {
        val before =
            driver.onClient {
                val snapshot = driver.work()
                action()
                snapshot
            }
        driver.await { before.renderExtractions < driver.work().renderExtractions }
        return before
    }

    private fun assertSamePresentation(
        before: ReactiveNativeWork,
        after: ReactiveNativeWork,
    ) {
        check(before.commands === after.commands && before.inputs === after.inputs)
        check(before.samePortable(after) && before.sameSources(after)) { "Stable content must retain its real native owners." }
    }
}
