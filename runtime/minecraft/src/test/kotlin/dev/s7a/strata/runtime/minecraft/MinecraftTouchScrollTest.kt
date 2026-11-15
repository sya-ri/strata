@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Button
import dev.s7a.strata.component.Column
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
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
import dev.s7a.strata.modifier.onTrailingItemsRequested
import dev.s7a.strata.modifier.size
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises deferred taps, item-origin drags, velocity decay, and cancellation through the real host.
 */
internal class MinecraftTouchScrollTest {
    @Test
    fun replacingTheScrollContextCancelsPendingTapsAndMomentumOnTheRetainedViewport() {
        for (fling in listOf(false, true)) {
            val state = mutableStateOf(ScrollState())
            var clicks = 0
            val definition =
                UiDefinition("Replaced scroll context") {
                    ScrollArea(state.value, Modifier.size(100, 60)) {
                        Column {
                            Button("Row", width = 80, modifier = Modifier.onActivate { clicks++ })
                            Spacer(Modifier.size(80, 180))
                        }
                    }
                }
            createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
                host.attach()
                host.frame(size, FrameTime(0))
                host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
                if (fling) {
                    host.frame(size, FrameTime(16_000_000))
                    host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
                    host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
                }
                val replacement = ScrollState()
                state.value = replacement
                host.frame(size, FrameTime(32_000_000))
                host.dispatchPointer(PointerEvent.Release(IntOffset(20, 10), PointerButton.Primary))
                host.frame(size, FrameTime(48_000_000))
                assertEquals(0.0, replacement.metrics.offset)
                assertEquals(0, clicks)
            }
        }
    }

    @Test
    fun tappingActivatesOnReleaseButDraggingTheSameButtonNeverActivates() {
        val state = ScrollState()
        var clicks = 0
        host(state) { clicks++ }.use { host ->
            host.attach()
            host.frame(size)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            assertEquals(0, clicks)
            host.dispatchPointer(PointerEvent.Move(IntOffset(21, 11)))
            host.dispatchPointer(PointerEvent.Release(IntOffset(21, 11), PointerButton.Primary))
            assertEquals(1, clicks)
            assertEquals(0.0, state.metrics.offset)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -30), PointerButton.Primary, 0.0, -40.0))
            host.frame(size)
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -30), PointerButton.Primary))
            assertEquals(1, clicks)
            assertEquals(40.0, state.metrics.offset)
        }
    }

    @Test
    fun releasingAFlickDeceleratesAndTouchingAgainStopsWithoutClicking() {
        val state = ScrollState()
        var clicks = 0
        host(state) { clicks++ }.use { host ->
            host.attach()
            host.frame(size, FrameTime(0))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.frame(size, FrameTime(16_000_000))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
            host.frame(size, FrameTime(32_000_000))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -30), PointerButton.Primary, 0.0, -20.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -30), PointerButton.Primary))
            val released = state.metrics.offset
            host.frame(size, FrameTime(48_000_000))
            val firstStep = state.metrics.offset - released
            host.frame(size, FrameTime(64_000_000))
            val secondStep = state.metrics.offset - released - firstStep
            assertTrue(0.0 < secondStep && secondStep < firstStep)
            val stopped = state.metrics.offset
            host.dispatchPointer(PointerEvent.Press(IntOffset(50, 30), PointerButton.Primary))
            host.frame(size, FrameTime(80_000_000))
            host.dispatchPointer(PointerEvent.Release(IntOffset(50, 30), PointerButton.Primary))
            host.frame(size, FrameTime(96_000_000))
            assertEquals(stopped, state.metrics.offset)
            assertEquals(0, clicks)
        }
    }

    @Test
    fun holdingStillBeforeReleaseDoesNotStartMomentum() {
        val state = ScrollState()
        host(state) {}.use { host ->
            host.attach()
            host.frame(size, FrameTime(0))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.frame(size, FrameTime(16_000_000))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
            host.frame(size, FrameTime(200_000_000))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
            host.frame(size, FrameTime(216_000_000))
            assertEquals(20.0, state.metrics.offset)
        }
    }

    @Test
    fun momentumDoesNotDependOnFrameRateAndStopsAtTheContentBoundary() {
        fun run(times: List<Long>): Double {
            val state = ScrollState()
            host(state) {}.use { host ->
                host.attach()
                host.frame(size, FrameTime(0))
                host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
                host.frame(size, FrameTime(16_000_000))
                host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
                host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
                times.forEach { host.frame(size, FrameTime(it)) }
                return state.metrics.offset
            }
        }
        assertEquals(run(listOf(32_000_000, 48_000_000, 64_000_000, 80_000_000)), run(listOf(80_000_000)), 0.000001)
        val state = ScrollState()
        host(state) {}.use { host ->
            host.attach()
            host.frame(size, FrameTime(0))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.frame(size, FrameTime(16_000_000))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -490), PointerButton.Primary, 0.0, -500.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -490), PointerButton.Primary))
            host.frame(size, FrameTime(500_000_000))
            assertEquals(state.metrics.maximumOffset, state.metrics.offset)
            host.frame(size, FrameTime(2_000_000_000))
            assertEquals(state.metrics.maximumOffset, state.metrics.offset)
        }
    }

    @Test
    fun removingAPendingItemAndDetachingACapturedDragCancelTheirOwnedWork() {
        val state = ScrollState()
        val visible = mutableStateOf(true)
        var clicks = 0
        val definition =
            UiDefinition("Removed tap") {
                ScrollArea(state, Modifier.size(100, 60)) {
                    Column {
                        if (visible.value) Button("Row", width = 80, modifier = Modifier.onActivate { clicks++ })
                        Spacer(Modifier.size(80, 180))
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(size, FrameTime(0))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            visible.value = false
            host.frame(size, FrameTime(16_000_000))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, 10), PointerButton.Primary))
            assertEquals(0, clicks)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.frame(size, FrameTime(32_000_000))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
            val stopped = state.metrics.offset
            host.detach()
            host.attach()
            host.frame(size, FrameTime(48_000_000))
            assertEquals(stopped, state.metrics.offset)
        }
    }

    @Test
    fun virtualRowsCanBeDraggedWithoutOpeningAndTappedAfterScrolling() {
        val state = VirtualListState<Int>()
        val clicked = ArrayList<Int>()
        val definition =
            UiDefinition("Touch list") {
                VirtualList(itemCount = 100, itemAt = { it }, keyAt = { it }, state = state, viewportSize = size, rowHeight = 20) { item ->
                    Button("Row $item", width = 100, modifier = Modifier.onActivate { clicked.add(item) })
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(size)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            assertTrue(clicked.isEmpty())
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -50), PointerButton.Primary, 0.0, -60.0))
            host.frame(size)
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -50), PointerButton.Primary))
            assertTrue(clicked.isEmpty())
            assertEquals(60.0, state.scrollState.metrics.offset)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            assertTrue(clicked.isEmpty())
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, 10), PointerButton.Primary))
            assertEquals(listOf(3), clicked)
        }
    }

    @Test
    fun slidersYieldVerticalDragsAndRetainHorizontalCapture() {
        val scroll = ScrollState()
        val slider = SliderState(0.5, 0.0..1.0)
        val definition =
            UiDefinition("Touch slider") {
                ScrollArea(scroll, Modifier.size(100, 60)) {
                    Column {
                        Slider("Value", slider, width = 80)
                        Spacer(Modifier.size(80, 180))
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(size)
            host.dispatchPointer(PointerEvent.Press(IntOffset(50, 10), PointerButton.Primary))
            assertEquals(0.5, slider.value)
            host.dispatchPointer(PointerEvent.Drag(IntOffset(50, -20), PointerButton.Primary, 0.0, -30.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(50, -20), PointerButton.Primary))
            assertEquals(30.0, scroll.metrics.offset)
            assertEquals(0.5, slider.value)
            scroll.scrollTo(0.0)
            host.frame(size)
            host.dispatchPointer(PointerEvent.Press(IntOffset(50, 10), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(150, 10), PointerButton.Primary, 100.0, 0.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(150, 10), PointerButton.Primary))
            assertEquals(1.0, slider.value)
            assertEquals(0.0, scroll.metrics.offset)
        }
    }

    @Test
    fun contentThatFitsStillUsesTapReleaseAndCancelsADraggedTap() {
        var clicks = 0
        val scroll = ScrollState()
        val definition =
            UiDefinition("Short touch content") {
                ScrollArea(scroll, Modifier.size(100, 60)) {
                    Button("Row", width = 80, modifier = Modifier.onActivate { clicks++ })
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(size)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            assertEquals(0, clicks)
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, 20), PointerButton.Primary, 0.0, 10.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, 20), PointerButton.Primary))
            assertEquals(0, clicks)
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, 10), PointerButton.Primary))
            assertEquals(1, clicks)
        }
    }

    @Test
    fun kineticVirtualScrollingCanLoadRowsAndPublishInputStateBeforeTheFrameCutoff() {
        val state = VirtualListState<Int>()
        val items = (0 until 8).toMutableList()
        val loaded = mutableStateOf(false)
        val definition =
            UiDefinition("Kinetic row loading") {
                VirtualList(
                    itemCount = { items.size },
                    itemAt = items::get,
                    keyAt = items::get,
                    state = state,
                    viewportSize = size,
                    rowHeight = 20,
                    canLoadTrailing = true,
                    modifier =
                        Modifier.onTrailingItemsRequested {
                            if (loaded.value.not()) {
                                items.addAll(8 until 16)
                                state.refresh()
                                loaded.value = true
                            }
                        },
                ) { Spacer(Modifier.size(100, 20)) }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(size, FrameTime(0))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 10), PointerButton.Primary))
            host.frame(size, FrameTime(16_000_000))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -10), PointerButton.Primary, 0.0, -20.0))
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -10), PointerButton.Primary))
            assertEquals(false, loaded.value)
            host.frame(size, FrameTime(96_000_000))
            assertTrue(loaded.value)
            assertEquals(320, state.scrollState.metrics.contentExtent)
        }
    }

    private fun host(
        state: ScrollState,
        onClick: () -> Unit,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            UiDefinition("Touch scrolling") {
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
