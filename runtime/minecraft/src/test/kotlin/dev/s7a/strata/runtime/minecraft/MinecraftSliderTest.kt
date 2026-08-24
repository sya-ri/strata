@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Slider
import dev.s7a.strata.component.SliderState
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.UiText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Verifies the common Minecraft Slider against version-owned track sampling policies.
 */
internal class MinecraftSliderTest {
    @Test
    fun legacyAtlasBorderRetainsExactSourceAndDestinationSlices() {
        val host = host(width = 150)
        host.attach()

        val commands = host.frame(IntSize(150, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(
            listOf(
                IntRect(0, 0, 20, 20),
                IntRect(20, 0, 130, 20),
                IntRect(130, 0, 150, 20),
            ),
            commands.take(3).map { command -> command.destination },
        )
        assertEquals(
            listOf(
                IntRect(0, 0, 20, 20),
                IntRect(20, 0, 130, 20),
                IntRect(180, 0, 200, 20),
            ),
            commands.take(3).map { command -> command.source },
        )
        host.close()
    }

    @Test
    fun narrowTrackClampsBordersAndOmitsAnEmptyCenterSlice() {
        val host = host(width = 30)
        host.attach()

        val commands = host.frame(IntSize(30, 20)).drawCommands.filterIsInstance<DrawCommand.BlitImage>()
        assertEquals(
            listOf(IntRect(0, 0, 15, 20), IntRect(15, 0, 30, 20)),
            commands.take(2).map { command -> command.destination },
        )
        assertEquals(
            listOf(IntRect(0, 0, 15, 20), IntRect(185, 0, 200, 20)),
            commands.take(2).map { command -> command.source },
        )
        host.close()
    }

    private fun host(width: Int): MinecraftUiHost {
        val profile =
            MinecraftProfileFixture.create(
                normalSliderBorder = 20,
                normalSliderCenterMode = NineSliceCenterMode.Tiled,
                highlightedSliderBorder = 20,
                highlightedSliderCenterMode = NineSliceCenterMode.Tiled,
            )
        return createMinecraftUiHost(
            ScreenDefinition(UiText.Literal("slider")) {
                Slider("A", SliderState(0.5), width = width)
            },
            profile,
        )
    }
}
