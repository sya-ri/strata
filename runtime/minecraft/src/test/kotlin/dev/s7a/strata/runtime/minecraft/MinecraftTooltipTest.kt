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
        for (padding in listOf(true, false)) {
            createMinecraftUiHost(
                ScreenDefinition("clipped-tooltip") {
                    Stack(Modifier.size(8, 6)) {
                        Spacer(Modifier.size(2, 2).tooltip("Long tooltip label", padding = padding, delayMillis = 0))
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
    fun componentPaddingChangesOnlyTheInsetAndPreservesTheProfileSurface() {
        val red = ArgbColor(0xFFFF0000.toInt())
        val green = ArgbColor(0xFF00FF00.toInt())
        val blue = ArgbColor(0xFF0000FF.toInt())
        for (padding in listOf(true, false)) {
            val host =
                createMinecraftUiHost(
                    ScreenDefinition("component-tooltip") {
                        Stack(Modifier.size(40, 30)) {
                            Spacer(
                                Modifier.size(4, 4).tooltip(delayMillis = 0, padding = padding) {
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
                val inset = if (padding) 3 else 0
                val content = IntRect(inset, 8 + inset, 8 + inset, 14 + inset)
                val rectangles = frame.drawCommands.filterIsInstance<DrawCommand.FillRectangle>()
                assertEquals(content, rectangles.single { it.color == red }.bounds)
                assertTrue(rectangles.any { it.color == green })
                assertTrue(rectangles.any { it.color == blue })
                assertContentPixels(frame, content, red)
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
        assertTrue(images.drop(firstTooltip).all { command -> 0 <= command.destination.left && command.destination.right <= 40 })
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

    private fun image(
        size: IntSize,
        color: Int,
    ) = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
}
