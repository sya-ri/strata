package dev.s7a.strata.integration.minecraft.fabric

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.Observe
import dev.s7a.strata.component.TextArea
import dev.s7a.strata.component.TextAreaState
import dev.s7a.strata.component.TextAreaViewport
import dev.s7a.strata.component.TextField
import dev.s7a.strata.component.TextFieldState
import dev.s7a.strata.component.TextInputAppearance
import dev.s7a.strata.component.TextStyle
import dev.s7a.strata.component.UiScope
import dev.s7a.strata.element.ElementKey
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.initialFocus
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.diagnostics.UiRenderMetric
import dev.s7a.strata.runtime.diagnostics.UiRenderMonitor
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi

/**
 * Verifies custom input frames on every loaded runtime using actual host progress and native pixels.
 * Editor composition and focus dispatch are tested independently through the shared Minecraft host.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal object TextInputAppearanceScenario {
    /**
     * Opens retained editors, changes only their appearance, and compares native output with a literal reference.
     * The caller owns screen cleanup, including on assertion failure.
     */
    fun verify(driver: ReactiveRenderTestDriver): String {
        val initial = appearance(0xFFF1F3F4.toInt())
        val changed = appearance(0xFFE0F0E0.toInt())
        val source = driver.onClient { ReactiveRenderSource(initial) }
        val field = driver.onClient { TextFieldState("Search") }
        val area = driver.onClient { TextAreaState("Draft\nSecond line") }
        val values = driver.onClient { field.value to area.value }
        val owner =
            driver.onClient {
                driver.open(
                    ScreenDefinition("Input appearance acceptance") {
                        Observe(source) { current -> editors(current, field, area) }
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
        val monitor = driver.onClient { owner.startRenderMonitoring() }
        try {
            val ids = driver.onClient { listOf(monitor.findNodes(fieldKey), monitor.findNodes(areaKey)) }
            val before =
                driver.onClient {
                    source.publish(changed)
                    driver.work()
                }
            driver.await { before.hostFrames < driver.work().hostFrames }
            driver.onClient {
                val snapshot = monitor.snapshot()
                check(snapshot.overflowed.not())
                check(snapshot.counts.getValue(UiRenderMetric.ObserveEvaluation) == 1L)
                check(snapshot.counts.getValue(UiRenderMetric.Measure) == 0L)
                check(snapshot.counts.getValue(UiRenderMetric.Layout) == 0L)
                check(0L < snapshot.counts.getValue(UiRenderMetric.Paint))
                check(ids.all { it.size == 1 })
                check(ids == listOf(monitor.findNodes(fieldKey), monitor.findNodes(areaKey)))
                check(field.value == values.first && area.value == values.second)
                check(before.preparations < driver.work().preparations)
            }
            verifyStable(driver, monitor, source, changed)
        } finally {
            driver.onClient { monitor.close() }
        }
        driver.assertPixels(
            ScreenDefinition("Input appearance reference") {
                editors(changed, TextFieldState("Search"), TextAreaState("Draft\nSecond line"))
            },
            ReactiveRenderCapture.InputAppearance,
        )
        return "inputAppearanceMeasure=0\ninputAppearanceLayout=0\ninputAppearanceRetainedNodes=2\ninputAppearanceStableFrames=100\ninputAppearancePixels=literal-headless-exact\n"
    }

    private fun verifyStable(
        driver: ReactiveRenderTestDriver,
        monitor: UiRenderMonitor,
        source: ReactiveRenderSource<TextInputAppearance.Custom>,
        changed: TextInputAppearance.Custom,
    ) {
        val stable =
            driver.onClient {
                monitor.checkpoint()
                source.publish(changed.copy())
                driver.work()
            }
        driver.await { 100L <= driver.work().hostFrames - stable.hostFrames }
        driver.onClient {
            val snapshot = monitor.snapshot()
            check(snapshot.overflowed.not())
            listOf(UiRenderMetric.ContentEvaluation, UiRenderMetric.NodeUpdate, UiRenderMetric.Measure, UiRenderMetric.Layout, UiRenderMetric.Paint).forEach {
                check(snapshot.counts.getValue(it) == 0L)
            }
            val after = driver.work()
            check(stable.preparations == after.preparations && stable.rasterizations == after.rasterizations && stable.uploads == after.uploads)
        }
    }

    private fun UiScope.editors(
        appearance: TextInputAppearance,
        field: TextFieldState,
        area: TextAreaState,
    ) {
        Column(modifier = Modifier.Empty.size(160, 64).background(ArgbColor(0xFF000000.toInt())), spacing = 4) {
            TextField(field, appearance, IntSize(160, 20), textStyle = TextStyle.ContainerLabel, modifier = Modifier.Empty.initialFocus(), key = fieldKey)
            TextArea(area, appearance, TextAreaViewport.Size(IntSize(160, 40)), textStyle = TextStyle.ContainerLabel, key = areaKey)
        }
    }

    private fun appearance(color: Int): TextInputAppearance.Custom {
        val frame = ImageSource.Pixels(createDrawImage(IntSize(3, 3), IntArray(9) { color }))
        return TextInputAppearance.Custom(frame, frame, ArgbColor(0xFF204020.toInt()))
    }

    private val fieldKey = ElementKey("appearance-field")
    private val areaKey = ElementKey("appearance-area")
}
