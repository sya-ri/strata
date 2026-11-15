@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.DragScrollOptions
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.onActivate
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises optional dragging and inertia without changing independent wheel and scrollbar input.
 */
internal class MinecraftDragScrollOptionsTest {
    @Test
    fun virtualizingAwayAMovingNestedViewportCancelsItsMomentum() {
        val outer = VirtualListState<Int>()
        val inner = List(100) { ScrollState() }
        val definition =
            UiDefinition("Nested viewport cleanup") {
                VirtualList(100, { it }, { it }, outer, IntSize(100, 80), 80) { item ->
                    ScrollArea(inner[item], Modifier.size(100, 80)) {
                        Column {
                            Button("Row $item", width = 80)
                            Spacer(Modifier.size(80, 180))
                        }
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            val viewport = IntSize(100, 80)
            host.attach()
            host.frame(viewport, FrameTime(0))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.frame(viewport, FrameTime(16_000_000))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
            outer.jumpToIndex(50)
            host.frame(viewport, FrameTime(32_000_000))
            host.frame(viewport, FrameTime(48_000_000))
            assertEquals(0.0, inner[50].metrics.offset)
        }
    }

    @Test
    fun tappingASliderReleasesItsCaptureBeforeDraggingAnotherItem() {
        val scroll = ScrollState()
        val slider = SliderState(0.5, 0.0..1.0)
        var clicks = 0
        val definition =
            UiDefinition("Slider tap then scroll") {
                ScrollArea(scroll, Modifier.size(100, 60)) {
                    Column {
                        Slider("Value", slider, width = 80)
                        Button("Row", width = 80, modifier = Modifier.onActivate { clicks++ })
                        Spacer(Modifier.size(80, 180))
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(size)
            host.dispatchPointer(PointerEvent.Press(IntOffset(50, 10), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Release(IntOffset(50, 10), PointerButton.Primary))
            val value = slider.value
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 30), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -40.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
            assertEquals(40.0, scroll.metrics.offset)
            assertEquals(value, slider.value)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun disabledDraggingKeepsImmediateActionsWheelAndScrollbarForBothViewportKinds() {
        for (virtual in listOf(false, true)) {
            val scroll = ScrollState(DragScrollOptions(enabled = false))
            val list = VirtualListState<Int>(scrollState = scroll)
            var clicks = 0
            val definition =
                UiDefinition("Optional drag") {
                    Row(spacing = 8) {
                        if (virtual) {
                            VirtualList(100, { it }, { it }, list, IntSize(100, 60), 20) {
                                Button("Row $it", width = 80, modifier = Modifier.onActivate { clicks++ })
                            }
                        } else {
                            ScrollArea(scroll, Modifier.size(100, 60)) {
                                Column {
                                    Button("Row", width = 80, modifier = Modifier.onActivate { clicks++ })
                                    Spacer(Modifier.size(80, 180))
                                }
                            }
                        }
                        Scrollbar(scroll, Modifier.size(6, 60))
                    }
                }
            createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
                host.attach()
                host.frame(IntSize(114, 60), FrameTime(0))
                host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
                assertEquals(1, clicks)
                host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -30), PointerButton.Primary, 0.0, -40.0))
                host.dispatchPointer(PointerEvent.Release(IntOffset(20, -30), PointerButton.Primary))
                host.frame(IntSize(114, 60), FrameTime(16_000_000))
                assertEquals(0.0, scroll.metrics.offset)
                host.dispatchPointer(PointerEvent.Press(IntOffset(95, 50), PointerButton.Primary))
                host.dispatchPointer(PointerEvent.Drag(IntOffset(95, 10), PointerButton.Primary, 0.0, -40.0))
                host.dispatchPointer(PointerEvent.Release(IntOffset(95, 10), PointerButton.Primary))
                assertEquals(0.0, scroll.metrics.offset)
                host.dispatchPointer(PointerEvent.Scroll(IntOffset(20, 10), 0.0, 1.0))
                assertTrue(0.0 < scroll.metrics.offset)
                host.dispatchPointer(PointerEvent.Press(IntOffset(110, 10), PointerButton.Primary))
                host.dispatchPointer(PointerEvent.Drag(IntOffset(110, 100), PointerButton.Primary, 0.0, 90.0))
                host.dispatchPointer(PointerEvent.Release(IntOffset(110, 100), PointerButton.Primary))
                assertEquals(scroll.metrics.maximumOffset, scroll.metrics.offset)
            }
        }
    }

    @Test
    fun inertiaCanBeDisabledWhileDraggingStillMovesTheContent() {
        val state = ScrollState(DragScrollOptions(inertiaEnabled = false))
        host(state).use { host ->
            flick(host)
            assertEquals(20.0, state.metrics.offset)
            host.frame(size, FrameTime(80_000_000))
            host.frame(size, FrameTime(160_000_000))
            assertEquals(20.0, state.metrics.offset)
        }
    }

    @Test
    fun customThresholdSeparatesATapFromAScrollAtTheConfiguredDistance() {
        val state = ScrollState(DragScrollOptions(dragThreshold = 8.0))
        var clicks = 0
        host(state) { clicks++ }.use { host ->
            host.attach()
            host.frame(size)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, 16), PointerButton.Primary, 0.0, 6.0))
            assertEquals(0.0, state.metrics.offset)
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, 16), PointerButton.Primary))
            assertEquals(1, clicks)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, 2), PointerButton.Primary, 0.0, -8.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, 2), PointerButton.Primary))
            assertEquals(8.0, state.metrics.offset)
            assertEquals(1, clicks)
        }
    }

    @Test
    fun largerDecayRatesReduceTheDistanceTraveledByTheSameFlick() {
        fun distance(deceleration: Double): Double {
            val state = ScrollState(DragScrollOptions(deceleration = deceleration))
            host(state).use { host ->
                flick(host)
                host.frame(size, FrameTime(80_000_000))
                return state.metrics.offset
            }
        }
        assertTrue(20.0 < distance(12.0))
        assertTrue(distance(12.0) < distance(3.0))
    }

    private fun flick(host: MinecraftUiHost) {
        host.attach()
        host.frame(size, FrameTime(0))
        host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
        host.frame(size, FrameTime(16_000_000))
        host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
        host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
    }

    private fun host(
        state: ScrollState,
        onClick: () -> Unit = {},
    ): MinecraftUiHost =
        createMinecraftUiHost(
            UiDefinition("Configured scroll") {
                ScrollArea(state, Modifier.size(100, 60)) {
                    Column {
                        Button("Row", width = 80, modifier = Modifier.onActivate { onClick() })
                        Spacer(Modifier.size(80, 180))
                    }
                }
            },
            MinecraftProfileFixture.create(),
        )

    private val size = IntSize(100, 60)
}
