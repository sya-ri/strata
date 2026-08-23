@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.ProgressBar
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.semantics.SemanticsRole
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies profile-backed determinate progress rendering and semantics.
 */
internal class MinecraftProgressBarTest {
    @Test
    fun halfProgressUsesOnlyHalfOfTheInnerWidthBeforePaintingTheBorder() {
        val border = image(IntSize(12, 12), 0xFF112233.toInt())
        val fill = image(IntSize(6, 6), 0xFF445566.toInt())
        val configuredHost =
            createMinecraftUiHost(
                ScreenDefinition("progress") { ProgressBar(0.5, IntSize(20, 12)) },
                MinecraftProfileFixture.create(progressBarBorder = border, progressBarFill = fill),
            )
        configuredHost.attach()
        val frame = configuredHost.frame(IntSize(20, 12))
        val fillCommands = frame.drawCommands.filterIsInstance<DrawCommand.BlitImage>().filter { command -> command.image === fill }
        assertTrue(fillCommands.isNotEmpty())
        assertTrue(fillCommands.all { command -> 2 <= command.destination.left && command.destination.right <= 10 })
        assertEquals(
            SemanticsRole.ProgressBar,
            frame.semantics
                .single()
                .semantics.role,
        )
        assertEquals(
            UiText.Literal("50%"),
            frame.semantics
                .single()
                .semantics.value,
        )
        configuredHost.close()
    }

    private fun image(
        size: IntSize,
        color: Int,
    ) = createDrawImage(size, IntArray(Math.multiplyExact(size.width, size.height)) { color })
}
