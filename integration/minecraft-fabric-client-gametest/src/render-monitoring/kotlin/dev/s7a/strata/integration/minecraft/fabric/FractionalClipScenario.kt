package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.scaleToFit
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Independent native viewport oracle: the expected image has no clip or transformed geometry.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object FractionalClipScenario {
    /**
     * Exercises a clipped row under the same three-quarter transform as a GUI-scale-four phone.
     * Successful host frames determine stability; the caller owns screen cleanup after success or failure.
     */
    fun verify(driver: ReactiveRenderTestDriver): String {
        driver.onClient {
            val state = VirtualListState<Int>()
            driver.open(
                ScreenDefinition("Fractional list clipping") {
                    Stack(Modifier.Empty.size(64, 64).background(ArgbColor(-1))) {
                        Column(Modifier.Empty.size(48, 48).scaleToFit(IntSize(64, 64))) {
                            Spacer(Modifier.Empty.size(64, 5))
                            VirtualList(itemCount = 1, itemAt = { it }, keyAt = { it }, state = state, viewportSize = IntSize(64, 10), rowHeight = 20) {
                                Spacer(Modifier.Empty.size(64, 20).background(GREEN))
                            }
                        }
                    }
                },
            )
        }
        driver.await { 0L < driver.work().preparations }
        var previous = driver.onClient { driver.work() }
        driver.await {
            val current = driver.work()
            val settled = previous.hostFrames < current.hostFrames && previous.preparations == current.preparations
            previous = current
            settled
        }
        val baseline = driver.onClient { driver.work() }
        driver.await { 100L <= driver.work().hostFrames - baseline.hostFrames }
        driver.onClient {
            val current = driver.work()
            check(current.preparations == baseline.preparations)
            check(current.rasterizations == baseline.rasterizations && current.uploads == baseline.uploads)
        }
        driver.assertPixels(
            ScreenDefinition("Exact viewport coverage") {
                Stack(Modifier.Empty.size(64, 64).background(ArgbColor(-1))) {
                    Column {
                        Spacer(Modifier.Empty.size(48, 4))
                        Spacer(Modifier.Empty.size(48, 7).background(GREEN))
                    }
                }
            },
            ReactiveRenderCapture.FractionalClip,
        )
        return "fractionalClipPixels=independent-unclipped-reference\nfractionalClipStableFrames=100\n"
    }

    private val GREEN = ArgbColor(0xffa9e88d.toInt())
}
