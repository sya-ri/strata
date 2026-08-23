@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Stack
import dev.s7a.strata.component.Text
import dev.s7a.strata.geometry.IntOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.input.PointerEvent
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.size
import dev.s7a.strata.modifier.tooltip
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies delayed root-overlay tooltip ordering and retained cache boundaries.
 */
internal class MinecraftTooltipTest {
    @Test
    fun tooltipAppearsAfterItsDelayAndAfterAllOrdinaryCommands() {
        val background = image(IntSize(100, 100), 0xF0100010.toInt())
        val frame = image(IntSize(100, 100), 0xFF5000FF.toInt())
        val host =
            createMinecraftUiHost(
                ScreenDefinition("tooltip") {
                    Stack(modifier = Modifier.Empty.size(40, 30)) {
                        Text("A", modifier = Modifier.Empty.tooltip("Tip", delayMillis = 500L))
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

    private fun image(
        size: IntSize,
        color: Int,
    ) = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
}
