@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Column
import dev.s7a.strata.component.CycleButton
import dev.s7a.strata.component.CycleButtonState
import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.VirtualList
import dev.s7a.strata.component.VirtualListState
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies wheel policy and captured scrolling through the real retained host.
 */
internal class MinecraftScrollInteractionTest {
    @Test
    fun cycleWheelDefaultsToEnabledAndCanBeDisabledOnTheRetainedControl() {
        val selection = CycleButtonState(listOf("A", "B"))
        val scroll = ScrollState()
        val disableWheel = mutableStateOf(false)
        val definition =
            UiDefinition("Wheel policy") {
                ScrollArea(scroll, Modifier.size(100, 60)) {
                    Column {
                        if (disableWheel.value) {
                            CycleButton(selection, width = 80, scrollEnabled = false)
                        } else {
                            CycleButton(selection, width = 80)
                        }
                        Spacer(Modifier.size(80, 180))
                    }
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(IntSize(100, 60))
            val position = IntOffset(20, 10)
            host.dispatchPointer(PointerEvent.Scroll(position, 0.0, 1.0))
            assertEquals("B", selection.value)
            assertEquals(0.0, scroll.metrics.offset)
            disableWheel.value = true
            host.frame(IntSize(100, 60))
            host.dispatchPointer(PointerEvent.Scroll(position, 0.0, 1.0))
            assertEquals("B", selection.value)
            assertEquals(9.0, scroll.metrics.offset)
            host.dispatchPointer(PointerEvent.Press(position, PointerButton.Primary))
            assertEquals("A", selection.value)
        }
    }

    @Test
    fun areaDragContinuesOutsideAndReleaseEndsCapture() {
        val scroll = ScrollState()
        val definition =
            UiDefinition("Drag area") {
                ScrollArea(scroll, Modifier.size(100, 60)) { Spacer(Modifier.size(80, 180)) }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(IntSize(100, 60))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 30), PointerButton.Primary))
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Drag(IntOffset(-10, -10), PointerButton.Primary, -30.0, -40.0)))
            assertEquals(40.0, scroll.metrics.offset)
            host.dispatchPointer(PointerEvent.Release(IntOffset(-10, -10), PointerButton.Primary))
            assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Drag(IntOffset(-10, -20), PointerButton.Primary, 0.0, -10.0)))
            assertEquals(40.0, scroll.metrics.offset)
        }
    }

    @Test
    fun scrollbarTrackAndCapturedThumbUseAbsolutePosition() {
        val scroll = ScrollState()
        val definition =
            UiDefinition("Drag scrollbar") {
                Row {
                    ScrollArea(scroll, Modifier.size(100, 60)) { Spacer(Modifier.size(80, 180)) }
                    Scrollbar(scroll, Modifier.size(6, 60))
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(IntSize(106, 60))
            scroll.scrollTo(9.25)
            host.frame(IntSize(106, 60))
            host.dispatchPointer(PointerEvent.Press(IntOffset(102, 10), PointerButton.Primary))
            assertEquals(9.25, scroll.metrics.offset)
            host.dispatchPointer(PointerEvent.Drag(IntOffset(102, 11), PointerButton.Primary, 0.0, 1.0))
            assertEquals(9.25 + scroll.metrics.maximumOffset / 28.0, scroll.metrics.offset, 0.000001)
            host.dispatchPointer(PointerEvent.Release(IntOffset(102, 11), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Press(IntOffset(102, 45), PointerButton.Primary))
            assertTrue(0.0 < scroll.metrics.offset)
            host.dispatchPointer(PointerEvent.Drag(IntOffset(400, -20), PointerButton.Primary, 298.0, -65.0))
            assertEquals(0.0, scroll.metrics.offset)
            host.dispatchPointer(PointerEvent.Drag(IntOffset(400, 150), PointerButton.Primary, 0.0, 170.0))
            assertEquals(scroll.metrics.maximumOffset, scroll.metrics.offset)
            host.dispatchPointer(PointerEvent.Release(IntOffset(400, 150), PointerButton.Primary))
            assertEquals(InputResult.Ignored, host.dispatchPointer(PointerEvent.Drag(IntOffset(400, -20), PointerButton.Primary, 0.0, -170.0)))
        }
    }

    @Test
    fun virtualListDragUsesPixelsAndRevealsTheCorrespondingRows() {
        val list = VirtualListState<Int>()
        val definition =
            UiDefinition("Drag list") {
                VirtualList(itemCount = 100, itemAt = { it }, keyAt = { it }, state = list, viewportSize = IntSize(100, 60), rowHeight = 20) {
                    Spacer(Modifier.size(100, 20))
                }
            }
        createMinecraftUiHost(definition, MinecraftProfileFixture.create()).use { host ->
            host.attach()
            host.frame(IntSize(100, 60))
            host.dispatchPointer(PointerEvent.Press(IntOffset(20, 30), PointerButton.Primary))
            host.dispatchPointer(PointerEvent.Drag(IntOffset(20, -30), PointerButton.Primary, 0.0, -60.0))
            host.frame(IntSize(100, 60))
            assertEquals(60.0, list.scrollState.metrics.offset)
            host.dispatchPointer(PointerEvent.Release(IntOffset(20, -30), PointerButton.Primary))
        }
    }
}
