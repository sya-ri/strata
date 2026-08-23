@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.LoadingIndicator
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.FrameTime
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Verifies native animation cells and retained frame-cache reuse between discrete changes.
 */
internal class MinecraftLoadingIndicatorTest {
    @Test
    fun timedFramesRepaintOnlyWhenTheNativeCellChanges() {
        val host =
            createMinecraftUiHost(
                ScreenDefinition("loading") { LoadingIndicator() },
                MinecraftProfileFixture.create(),
            )
        host.attach()
        val initial = host.frame(IntSize(10, 4), FrameTime(0L))
        val sameCell = host.frame(IntSize(10, 4), FrameTime(299_999_999L))
        assertSame(initial, sameCell)

        val nextCell = host.frame(IntSize(10, 4), FrameTime(300_000_000L))
        assertNotSame(initial, nextCell)
        val initialSource =
            initial.drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()
                .single()
                .source
        val nextSource =
            nextCell.drawCommands
                .filterIsInstance<DrawCommand.BlitImage>()
                .single()
                .source
        assertEquals(0, initialSource.top)
        assertEquals(2, nextSource.top)

        val repeated = host.frame(IntSize(10, 4), FrameTime(300_000_000L))
        assertSame(nextCell, repeated)
        host.close()
    }
}
