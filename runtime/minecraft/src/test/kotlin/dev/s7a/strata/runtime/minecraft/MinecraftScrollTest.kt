@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Row
import dev.s7a.strata.component.ScrollArea
import dev.s7a.strata.component.ScrollState
import dev.s7a.strata.component.Scrollbar
import dev.s7a.strata.component.Spacer
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.InputResult
import dev.s7a.strata.input.PointerButton
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.onPointerEvent
import dev.s7a.strata.modifier.size
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies independent profile-backed ScrollArea and Scrollbar components.
 */
internal class MinecraftScrollTest {
    @Test
    fun consecutiveScrollThenMoveOrPressUsesFreshHitGeometryWithoutAFrame() {
        val position = IntOffset(20, 20)
        for (next in listOf(PointerEvent.Move(position), PointerEvent.Press(position, PointerButton.Primary))) {
            val state = ScrollState()
            val observed = ArrayList<Pair<PointerEvent, IntOffset>>()
            val host =
                createMinecraftUiHost(
                    ScreenDefinition("continuous scroll") {
                        ScrollArea(state, modifier = Modifier.Empty.size(100, 50)) {
                            Spacer(
                                modifier =
                                    Modifier.Empty.size(80, 180).background(contentColor).onPointerEvent { event, local ->
                                        if (event is PointerEvent.Scroll) {
                                            InputResult.Ignored
                                        } else {
                                            observed.add(event to local)
                                            if (event is PointerEvent.Press) state.scrollTo(0.0)
                                            InputResult.Consumed
                                        }
                                    },
                            )
                        }
                    },
                    MinecraftProfileFixture.create(),
                )
            host.attach()
            host.frame(IntSize(100, 50))
            assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(position, 0.0, 1.0)))
            assertEquals(9.0, state.metrics.offset)
            assertEquals(InputResult.Consumed, host.dispatchPointer(next))
            assertEquals(next to IntOffset(10, 27), observed.last())
            if (next is PointerEvent.Press) {
                val release = PointerEvent.Release(position, PointerButton.Primary)
                assertEquals(InputResult.Consumed, host.dispatchPointer(release))
                assertEquals(release to IntOffset(10, 18), observed.last())
            }
            val committed = host.frame(IntSize(100, 50))
            assertEquals(
                2 - state.metrics.offset.toInt(),
                committed.drawCommands
                    .filterIsInstance<DrawCommand.FillRectangle>()
                    .single()
                    .bounds.top,
            )
            host.close()
        }
    }

    @Test
    fun areaAndSeparatelyPlacedScrollbarShareOneState() {
        val assets = ScrollAssets()
        val state = ScrollState()
        val host = host(assets.profile(), state, includeScrollbar = true)
        host.attach()

        val initial = host.frame(viewport)
        assertEquals(94, state.metrics.viewportExtent)
        assertEquals(184, state.metrics.contentExtent)
        assertTrue(state.metrics.canScroll)
        val initialThumb = initial.drawCommands.filterIsInstance<DrawCommand.BlitImage>().filter { it.image === assets.scrollbarThumb }
        assertTrue(initialThumb.isNotEmpty())

        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Scroll(IntOffset(20, 20), 0.0, 1.0)))
        val scrolled = host.frame(viewport)
        assertEquals(9.0, state.metrics.offset)
        assertEquals(
            IntRect(15, -7, 285, 173),
            scrolled.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds,
        )
        val movedThumb = scrolled.drawCommands.filterIsInstance<DrawCommand.BlitImage>().filter { it.image === assets.scrollbarThumb }
        assertFalse(initialThumb.first().destination == movedThumb.first().destination)
        host.close()
    }

    @Test
    fun areaWorksWithoutScrollbarAndSupportsProgrammaticMovement() {
        val state = ScrollState(initialOffset = 18.0)
        val host = host(MinecraftProfileFixture.create(), state, includeScrollbar = false)
        host.attach()

        val initial = host.frame(IntSize(300, 94))
        assertEquals(18.0, state.metrics.offset)
        assertTrue(
            initial.drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .single()
                .bounds.top < 0,
        )
        state.scrollTo(Double.MAX_VALUE)
        host.frame(IntSize(300, 94))
        assertEquals(state.metrics.maximumOffset, state.metrics.offset)
        host.close()
    }

    @Test
    fun detachedScrollbarCanDragTheLinkedArea() {
        val state = ScrollState()
        val host = host(MinecraftProfileFixture.create(), state, includeScrollbar = true)
        host.attach()
        host.frame(viewport)

        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Press(IntOffset(308, 10), PointerButton.Primary)))
        assertEquals(
            InputResult.Consumed,
            host.dispatchPointer(PointerEvent.Drag(IntOffset(308, 30), PointerButton.Primary, 0.0, 20.0)),
        )
        assertTrue(0.0 < state.metrics.offset)
        host.frame(viewport)
        assertEquals(InputResult.Consumed, host.dispatchPointer(PointerEvent.Release(IntOffset(308, 30), PointerButton.Primary)))
        host.close()
    }

    @Test
    fun nonScrollableStateSuppressesIndependentScrollbarPixels() {
        val assets = ScrollAssets()
        val state = ScrollState()
        val host =
            createMinecraftUiHost(
                ScreenDefinition("scroll") {
                    Row(spacing = 8) {
                        ScrollArea(state, modifier = Modifier.Empty.size(100, 40)) {
                            Spacer(modifier = Modifier.Empty.size(80, 20).background(contentColor))
                        }
                        Scrollbar(state, modifier = Modifier.Empty.size(6, 40))
                    }
                },
                assets.profile(),
            )
        host.attach()
        val frame = host.frame(IntSize(114, 40))
        val images = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>().map { it.image }
        assertTrue(images.none { it === assets.scrollbarBackground || it === assets.scrollbarThumb })
        host.close()
    }

    private fun host(
        profile: MinecraftUiProfile,
        state: ScrollState,
        includeScrollbar: Boolean,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("scroll") {
                Row(spacing = 8) {
                    ScrollArea(state, modifier = Modifier.Empty.size(300, 94)) {
                        Spacer(modifier = Modifier.Empty.size(270, 180).background(contentColor))
                    }
                    if (includeScrollbar) {
                        Scrollbar(state, modifier = Modifier.Empty.size(6, 94))
                    }
                }
            },
            profile,
        )

    private class ScrollAssets {
        val listBackground: DrawImage = image(IntSize(16, 16), 0xFF101010.toInt())
        val headerSeparator: DrawImage = image(IntSize(32, 2), 0xFF202020.toInt())
        val footerSeparator: DrawImage = image(IntSize(32, 2), 0xFF303030.toInt())
        val scrollbarBackground: DrawImage = image(IntSize(6, 32), 0xFF404040.toInt())
        val scrollbarThumb: DrawImage = image(IntSize(6, 32), 0xFF505050.toInt())

        fun profile(): MinecraftUiProfile =
            MinecraftProfileFixture.create(
                listBackground = listBackground,
                listHeaderSeparator = headerSeparator,
                listFooterSeparator = footerSeparator,
                scrollbarBackground = scrollbarBackground,
                scrollbarThumb = scrollbarThumb,
            )
    }

    private companion object {
        val viewport = IntSize(314, 94)
        val contentColor = ArgbColor(0xFFABCDEF.toInt())

        fun image(
            size: IntSize,
            color: Int,
        ): DrawImage = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
    }
}
