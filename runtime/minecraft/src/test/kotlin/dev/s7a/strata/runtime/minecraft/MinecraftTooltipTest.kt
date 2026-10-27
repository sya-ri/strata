@file:Suppress("DEPRECATION") // Compatibility overloads and regression coverage retain the deprecated screen entry points.

@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Spacer
import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.background
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.tooltip
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.runtime.spi.RuntimeUiFrame
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.state.mutableStateOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies delayed root-overlay tooltip ordering and retained cache boundaries.
 */
internal class MinecraftTooltipTest {
    @Test
    fun longStringTooltipsClipInsideTinyViewportsWhileOrdinaryTextKeepsItsNaturalSizeContract() {
        val profile = MinecraftProfileFixture.create()
        for (decorated in listOf(true, false)) {
            createMinecraftUiHost(
                ScreenDefinition("clipped-tooltip") {
                    Stack(Modifier.size(8, 6)) {
                        Spacer(Modifier.size(2, 2).tooltip("Long tooltip label", decorated = decorated, delayMillis = 0))
                    }
                },
                profile,
            ).use { host ->
                host.attach()
                host.frame(IntSize(8, 6))
                host.dispatchPointer(PointerEvent.Move(IntOffset.Zero))
                val frame = host.frame(IntSize(8, 6))
                assertEquals(IntSize(8, 6), rasterizeHeadless(frame.drawCommands, frame.size).size)
            }
        }
        createMinecraftUiHost(ScreenDefinition("ordinary-text") { Text("Long tooltip label") }, profile).use { host ->
            host.attach()
            assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(8, 6)) }
        }
    }

    @Test
    fun componentDecorationControlsTheSurfaceAndInset() {
        val red = ArgbColor(0xFFFF0000.toInt())
        val green = ArgbColor(0xFF00FF00.toInt())
        val blue = ArgbColor(0xFF0000FF.toInt())
        for (decorated in listOf(true, false)) {
            val host =
                createMinecraftUiHost(
                    ScreenDefinition("component-tooltip") {
                        Stack(Modifier.size(40, 30)) {
                            Spacer(
                                Modifier.size(4, 4).tooltip(delayMillis = 0, decorated = decorated) {
                                    Spacer(Modifier.size(8, 6).background(red))
                                },
                            )
                        }
                    },
                    MinecraftProfileFixture.create(legacyTooltipColors = Triple(green, blue, blue)),
                )
            host.use {
                host.attach()
                host.frame(IntSize(40, 30))
                host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
                val frame = host.frame(IntSize(40, 30))
                val inset = if (decorated) 3 else 0
                val content = IntRect(inset, 8 + inset, 8 + inset, 14 + inset)
                val rectangles = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
                assertEquals(content, rectangles.single { it.color == red }.bounds)
                assertEquals(decorated, rectangles.any { it.color == green })
                assertEquals(decorated, rectangles.any { it.color == blue })
                assertContentPixels(frame, content, red)
                if (decorated) {
                    val surface = IntRect(0, 8, 8 + inset * 2, 14 + inset * 2)
                    assertFramePixels(frame, surface, blue)
                }
            }
        }
    }

    @Test
    fun changingDecorationKeepsAnAlreadyVisibleTooltipVisible() {
        val decorated = mutableStateOf(true)
        val red = ArgbColor(0xFFFF0000.toInt())
        val blue = ArgbColor(0xFF0000FF.toInt())
        createMinecraftUiHost(
            ScreenDefinition("changing-decoration") {
                Stack(Modifier.size(40, 30)) {
                    Spacer(
                        Modifier.size(4, 4).tooltip(decorated = decorated.value) {
                            Spacer(Modifier.size(8, 6).background(red))
                        },
                    )
                }
            },
            MinecraftProfileFixture.create(legacyTooltipColors = Triple(blue, blue, blue)),
        ).use { host ->
            host.attach()
            val viewport = IntSize(40, 30)
            host.frame(viewport, FrameTime(0))
            host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
            host.frame(viewport, FrameTime(0))
            listOf(true, false, true).forEachIndexed { index, enabled ->
                decorated.value = enabled
                val frame = host.frame(viewport, FrameTime(500_000_000L + index))
                val rectangles = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
                val inset = if (enabled) 3 else 0
                assertEquals(IntRect(inset, 8 + inset, 8 + inset, 14 + inset), rectangles.single { it.color == red }.bounds)
                assertEquals(enabled, rectangles.any { it.color == blue })
            }
        }
    }

    @Test
    fun spriteFrameSurroundsOpaqueContentWithoutMovingItsOrigin() {
        val blue = ArgbColor(0xFF0000FF.toInt())
        val red = ArgbColor(0xFFFF0000.toInt())
        val background =
            createDrawImage(
                IntSize(100, 100),
                IntArray(10_000) { index ->
                    if (index % 100 in 9..90 && index / 100 in 9..90) 0xF0100010.toInt() else 0
                },
            )
        val border =
            createDrawImage(
                IntSize(100, 100),
                IntArray(10_000) { index ->
                    val x = index % 100
                    val y = index / 100
                    val horizontalEdge = (y == 9 || y == 90) && x in 9..90
                    val verticalEdge = (x == 9 || x == 90) && y in 9..90
                    if (horizontalEdge || verticalEdge) blue.value else 0
                },
            )
        for (decorated in listOf(true, false)) {
            createMinecraftUiHost(
                ScreenDefinition("sprite-tooltip") {
                    Stack(Modifier.size(40, 40)) {
                        Spacer(
                            Modifier.size(4, 4).tooltip(delayMillis = 0, decorated = decorated) {
                                Spacer(Modifier.size(32, 24).background(red))
                            },
                        )
                    }
                },
                MinecraftProfileFixture.create(tooltipBackground = background, tooltipFrame = border),
            ).use { host ->
                host.attach()
                host.frame(IntSize(40, 40))
                host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
                val frame = host.frame(IntSize(40, 40))
                val inset = if (decorated) 3 else 0
                val content = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>().single()
                assertEquals(IntRect(inset, 8 + inset, 32 + inset, 32 + inset), content.bounds)
                if (decorated) {
                    assertFramePixels(frame, IntRect(0, 8, 32 + inset * 2, 32 + inset * 2), blue)
                } else {
                    assertTrue(frame.drawCommands.none { it is DrawCommand.BlitImage })
                    assertContentPixels(frame, content.bounds, red)
                }
                val image = rasterizeHeadless(frame.drawCommands, frame.size)
                assertEquals(red.value, image.copyArgb()[20 * image.size.width + 16])
            }
        }
    }

    @Test
    fun tooltipAppearsAfterItsDelayAndAfterAllOrdinaryCommands() {
        val background = image(IntSize(100, 100), 0xF0100010.toInt())
        val frame = image(IntSize(100, 100), 0xFF5000FF.toInt())
        val host =
            createMinecraftUiHost(
                ScreenDefinition("tooltip") {
                    Stack(modifier = Modifier.size(40, 30)) {
                        Text("A", modifier = Modifier.tooltip("Tip", delayMillis = 500L))
                    }
                },
                MinecraftProfileFixture.create(tooltipBackground = background, tooltipFrame = frame),
            )
        host.attach()
        host.frame(IntSize(40, 30), FrameTime(0L))
        host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))
        val initial = host.frame(IntSize(40, 30), FrameTime(0L))
        val waiting = host.frame(IntSize(40, 30), FrameTime(499_999_999L))
        assertSame(initial, waiting)

        val visible = host.frame(IntSize(40, 30), FrameTime(500_000_000L))
        assertTrue(initial.drawCommands.size < visible.drawCommands.size)
        val images = visible.drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        val firstTooltip = images.indexOfFirst { command -> command.image === background }
        assertTrue(0 < firstTooltip)
        assertTrue(images.drop(firstTooltip).any { command -> command.image === frame })
        assertTrue(images.indexOfLast { command -> command.image === frame } < images.lastIndex)
        assertTrue(visible.drawCommands.any { command -> command is DrawCommand.PushClip && command.bounds == IntRect(0, 0, 40, 30) })
        assertEquals(visible.size, rasterizeHeadless(visible.drawCommands, visible.size).size)
        host.close()
    }

    @Test
    fun legacyTooltipUsesCodeDefinedBackgroundAndGradientColors() {
        val background = ArgbColor(0xF0100010.toInt())
        val borderTop = ArgbColor(0x505000FF)
        val borderBottom = ArgbColor(0x5028007F)
        val host =
            createMinecraftUiHost(
                ScreenDefinition("legacy-tooltip") {
                    Stack(modifier = Modifier.size(40, 30)) {
                        Text("A", modifier = Modifier.tooltip("Tip", delayMillis = 0L))
                    }
                },
                MinecraftProfileFixture.create(legacyTooltipColors = Triple(background, borderTop, borderBottom)),
            )
        host.attach()
        host.frame(IntSize(40, 30), FrameTime(0L))
        host.dispatchPointer(PointerEvent.Move(IntOffset(1, 1)))

        val colors =
            host
                .frame(IntSize(40, 30), FrameTime(0L))
                .drawCommands
                .filterIsInstance<DrawCommand.FillRectangle>()
                .map { command -> command.color }
        assertTrue(background in colors)
        assertTrue(borderTop in colors)
        assertTrue(borderBottom in colors)
        host.close()
    }

    /**
     * Checks all pixels against the independently specified content rectangle at each physical density.
     */
    private fun assertContentPixels(
        frame: RuntimeUiFrame,
        content: IntRect,
        color: ArgbColor,
    ) {
        for (scale in 1..3) {
            val width = frame.size.width * scale
            val pixels = rasterizeHeadless(frame.drawCommands, frame.size, scale).copyArgb()
            pixels.forEachIndexed { index, pixel ->
                val x = index % width / scale
                val y = index / width / scale
                val inside = x in content.left until content.right && y in content.top until content.bottom
                assertEquals(inside, pixel == color.value)
            }
        }
    }

    /**
     * Requires visible frame edges at every physical density.
     */
    private fun assertFramePixels(
        frame: RuntimeUiFrame,
        surface: IntRect,
        color: ArgbColor,
    ) {
        for (scale in 1..3) {
            val image = rasterizeHeadless(frame.drawCommands, frame.size, scale)
            val pixels = image.copyArgb()
            for (y in surface.top until surface.bottom) {
                for (x in surface.left until surface.right) {
                    val horizontalEdge = x == surface.left || x == surface.right - 1
                    val verticalEdge = y == surface.top || y == surface.bottom - 1
                    if (horizontalEdge || verticalEdge) {
                        assertEquals(color.value, pixels[y * scale * image.size.width + x * scale])
                    }
                }
            }
        }
    }

    private fun image(
        size: IntSize,
        color: Int,
    ) = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
}
