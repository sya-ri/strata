package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.ImageScale
import dev.s7a.strata.component.ImageSource
import dev.s7a.strata.component.NineSliceCenterMode
import dev.s7a.strata.component.Stack
import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.Insets
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.modifier.imageBackground
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies arbitrary resource-pack image components and backgrounds through the public common boundary.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftImageTest {
    @Test
    fun assetIdentifiersAreStructuralAndValidateBothParts() {
        val first = ResourceId("example", "textures/gui/panel.png")
        val equal = ResourceId("example", "textures/gui/panel.png")
        val other = ResourceId("example", "textures/gui/other.png")

        assertEquals("example", first.namespace)
        assertEquals("textures/gui/panel.png", first.path)
        assertEquals(first, equal)
        assertEquals(first.hashCode(), equal.hashCode())
        assertNotSame(first, equal)
        assertEquals(false, first == other)
        assertThrows(IllegalArgumentException::class.java) { ResourceId("Example", "textures/gui/panel.png") }
        assertThrows(IllegalArgumentException::class.java) { ResourceId("example", "../panel.png") }
    }

    @Test
    fun imageMapsCompleteSourceToRequestedLogicalSize() {
        val source =
            createDrawImage(
                IntSize(2, 2),
                intArrayOf(
                    0xFFFF0000.toInt(),
                    0xFF00FF00.toInt(),
                    0xFF0000FF.toInt(),
                    0xFFFFFFFF.toInt(),
                ),
            )
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Image") {
                    Image(ImageSource.Pixels(source), IntSize(4, 4))
                },
                MinecraftProfileFixture.create(),
            )
        try {
            host.attach()
            val frame = host.frame(IntSize(4, 4))
            val command = frame.drawCommands.single() as DrawCommand.SampledImage
            assertSame(source, command.image)
            assertEquals(FloatRect(0f, 0f, 2f, 2f), command.source)
            assertEquals(FloatRect(0f, 0f, 4f, 4f), command.destination)
            assertEquals(0f, command.alphaCutoff)
            val rendered = rasterizeHeadless(frame.drawCommands, IntSize(4, 4))
            assertEquals(0xFFFF0000.toInt(), rendered.argbAt(0, 0))
            assertEquals(0xFF00FF00.toInt(), rendered.argbAt(3, 0))
            assertEquals(0xFF0000FF.toInt(), rendered.argbAt(0, 3))
            assertEquals(0xFFFFFFFF.toInt(), rendered.argbAt(3, 3))
        } finally {
            host.close()
        }
    }

    @Test
    fun imageMapsOnlyTheRequestedSourceRegionAndValidatesBounds() {
        val source = createDrawImage(IntSize(3, 2), IntArray(6) { index -> 0xFF000000.toInt() or index })
        val host =
            createMinecraftUiHost(
                ScreenDefinition("Image region") {
                    Image(ImageSource.Pixels(source), IntRect(1, 0, 3, 2), IntSize(4, 2))
                },
                MinecraftProfileFixture.create(),
            )
        try {
            host.attach()
            val frame = host.frame(IntSize(4, 2))
            val command = frame.drawCommands.single() as DrawCommand.SampledImage
            assertSame(source, command.image)
            assertEquals(FloatRect(1f, 0f, 3f, 2f), command.source)
            assertEquals(FloatRect(0f, 0f, 4f, 2f), command.destination)
            assertEquals(0f, command.alphaCutoff)
            val rendered = rasterizeHeadless(frame.drawCommands, IntSize(4, 2))
            assertEquals(source.argbAt(1, 0), rendered.argbAt(0, 0))
            assertEquals(source.argbAt(2, 1), rendered.argbAt(3, 1))
        } finally {
            host.close()
        }

        listOf(
            IntRect(0, 0, 0, 1),
            IntRect(-1, 0, 1, 1),
            IntRect(0, 0, 4, 1),
            IntRect(0, 0, 1, 3),
        ).forEach { invalid ->
            val invalidHost =
                createMinecraftUiHost(
                    ScreenDefinition("Invalid image region") {
                        Image(ImageSource.Pixels(source), invalid)
                    },
                    MinecraftProfileFixture.create(),
                )
            assertThrows(IllegalArgumentException::class.java) { invalidHost.attach() }
            invalidHost.close()
        }
    }

    @Test
    fun backgroundSupportsStretchAndRowMajorTiles() {
        val source = createDrawImage(IntSize(2, 2), IntArray(4) { 0xFF123456.toInt() })
        val stretched = hostWithBackground(source, ImageScale.Stretch)
        val tiled = hostWithBackground(source, ImageScale.Tile)
        try {
            stretched.attach()
            val stretchCommand = stretched.frame(IntSize(5, 3)).drawCommands.single() as DrawCommand.BlitImage
            assertEquals(IntRect(0, 0, 5, 3), stretchCommand.destination)

            tiled.attach()
            val tileCommands = tiled.frame(IntSize(5, 3)).drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(
                listOf(
                    IntRect(0, 0, 2, 2),
                    IntRect(2, 0, 4, 2),
                    IntRect(4, 0, 5, 2),
                    IntRect(0, 2, 2, 3),
                    IntRect(2, 2, 4, 3),
                    IntRect(4, 2, 5, 3),
                ),
                tileCommands.map { command -> command.destination },
            )
            assertEquals(
                listOf(
                    IntRect(0, 0, 2, 2),
                    IntRect(0, 0, 2, 2),
                    IntRect(0, 0, 1, 2),
                    IntRect(0, 0, 2, 1),
                    IntRect(0, 0, 2, 1),
                    IntRect(0, 0, 1, 1),
                ),
                tileCommands.map { command -> command.source },
            )
            tileCommands.forEach { command -> assertSame(source, command.image) }
        } finally {
            stretched.close()
            tiled.close()
        }
    }

    @Test
    fun nineSliceUsesNativeVerticalOrderAndClippedTiles() {
        val source =
            createDrawImage(
                IntSize(4, 4),
                IntArray(16) { index -> 0xFF000000.toInt() or index },
            )
        val host = hostWithNineSliceBackground(source, Insets.all(1), NineSliceCenterMode.Tiled)
        try {
            host.attach()
            val commands = host.frame(IntSize(4, 7)).drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(
                listOf(
                    IntRect(0, 0, 4, 1),
                    IntRect(0, 1, 4, 3),
                    IntRect(0, 3, 4, 5),
                    IntRect(0, 5, 4, 6),
                    IntRect(0, 6, 4, 7),
                ),
                commands.map { command -> command.destination },
            )
            assertEquals(
                listOf(
                    IntRect(0, 0, 4, 1),
                    IntRect(0, 1, 4, 3),
                    IntRect(0, 1, 4, 3),
                    IntRect(0, 1, 4, 2),
                    IntRect(0, 3, 4, 4),
                ),
                commands.map { command -> command.source },
            )
            commands.forEach { command -> assertSame(source, command.image) }
            val rendered = rasterizeHeadless(commands, IntSize(4, 7))
            assertEquals(source.argbAt(2, 0), rendered.argbAt(2, 0))
            assertEquals(source.argbAt(2, 1), rendered.argbAt(2, 5))
            assertEquals(source.argbAt(2, 3), rendered.argbAt(2, 6))
        } finally {
            host.close()
        }
    }

    @Test
    fun nineSliceUsesRowMajorGridAndValidatesSourceCenters() {
        val source = createDrawImage(IntSize(3, 3), IntArray(9) { index -> 0xFF101010.toInt() + index })
        val host = hostWithNineSliceBackground(source, Insets.all(1), NineSliceCenterMode.Stretched)
        try {
            host.attach()
            val commands = host.frame(IntSize(5, 5)).drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(9, commands.size)
            assertEquals(
                listOf(
                    IntRect(0, 0, 1, 1),
                    IntRect(1, 0, 4, 1),
                    IntRect(4, 0, 5, 1),
                    IntRect(0, 1, 1, 4),
                    IntRect(1, 1, 4, 4),
                    IntRect(4, 1, 5, 4),
                    IntRect(0, 4, 1, 5),
                    IntRect(1, 4, 4, 5),
                    IntRect(4, 4, 5, 5),
                ),
                commands.map { command -> command.destination },
            )
        } finally {
            host.close()
        }

        val invalid = hostWithNineSliceBackground(source, Insets(left = 1, right = 2), NineSliceCenterMode.Tiled)
        assertThrows(IllegalArgumentException::class.java) { invalid.attach() }
        invalid.close()
    }

    private fun hostWithBackground(
        source: DrawImage,
        scale: ImageScale,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Background") {
                Stack(modifier = Modifier.Empty.imageBackground(ImageSource.Pixels(source), scale)) {}
            },
            MinecraftProfileFixture.create(),
        )

    private fun hostWithNineSliceBackground(
        source: DrawImage,
        border: Insets,
        centerMode: NineSliceCenterMode,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Nine slice") {
                Stack(modifier = Modifier.Empty.imageBackground(ImageSource.Pixels(source), border, centerMode)) {}
            },
            MinecraftProfileFixture.create(),
        )
}
