package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.Image
import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.headless.rasterizeHeadless
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.screen.ScreenDefinition
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies the reusable player-head component against Minecraft 26.2 face-layer geometry.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftPlayerHeadTest {
    @Test
    fun faceAndHatUseNativeSourcesAndOrder() {
        val skin = testSkin()
        val host = host(skin, showHat = true)
        try {
            host.attach()
            val commands = host.frame(IntSize(24, 24)).drawCommands.map { command -> command as DrawCommand.BlitImage }
            assertEquals(2, commands.size)
            commands.forEach { command -> assertSame(skin, command.image) }
            assertEquals(listOf(IntRect(8, 8, 16, 16), IntRect(40, 8, 48, 16)), commands.map { command -> command.source })
            assertEquals(List(2) { IntRect(0, 0, 24, 24) }, commands.map { command -> command.destination })

            val rendered = rasterizeHeadless(commands, IntSize(24, 24))
            assertEquals(0xFF7F0080.toInt(), rendered.argbAt(0, 0))
            assertEquals(0xFF7F0080.toInt(), rendered.argbAt(23, 23))
        } finally {
            host.close()
        }
    }

    @Test
    fun hatCanBeOmittedWithoutChangingFaceMapping() {
        val skin = testSkin()
        val host = host(skin, showHat = false, size = 12)
        try {
            host.attach()
            val command = host.frame(IntSize(12, 12)).drawCommands.single() as DrawCommand.BlitImage
            assertSame(skin, command.image)
            assertEquals(IntRect(8, 8, 16, 16), command.source)
            assertEquals(IntRect(0, 0, 12, 12), command.destination)
            assertEquals(0xFFFF0000.toInt(), rasterizeHeadless(listOf(command), IntSize(12, 12)).argbAt(6, 6))
        } finally {
            host.close()
        }
    }

    @Test
    fun sourceSizeLogicalSizeAndConstraintsAreValidated() {
        val wrongSkin = createDrawImage(IntSize(63, 64), IntArray(63 * 64))
        val wrongSource = host(wrongSkin, showHat = true)
        try {
            assertThrows(IllegalArgumentException::class.java) { wrongSource.attach() }
        } finally {
            wrongSource.close()
        }
        val wrongSize = host(testSkin(), showHat = true, size = 0)
        try {
            assertThrows(IllegalArgumentException::class.java) { wrongSize.attach() }
        } finally {
            wrongSize.close()
        }

        val host = host(testSkin(), showHat = true, size = 24)
        try {
            host.attach()
            assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(23, 24)) }
        } finally {
            host.close()
        }
    }

    private fun host(
        skin: DrawImage,
        showHat: Boolean,
        size: Int = 24,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Player head") { PlayerHead(PlayerSkinSource.Pixels(skin), size, showHat) },
            MinecraftProfileFixture.create(),
        )

    private fun testSkin(): DrawImage {
        val pixels = IntArray(64 * 64)
        fill(pixels, IntRect(8, 8, 16, 16), 0xFFFF0000.toInt())
        fill(pixels, IntRect(40, 8, 48, 16), 0x800000FF.toInt())
        return createDrawImage(IntSize(64, 64), pixels)
    }

    private fun fill(
        pixels: IntArray,
        rectangle: IntRect,
        color: Int,
    ) {
        for (y in rectangle.top until rectangle.bottom) {
            for (x in rectangle.left until rectangle.right) {
                pixels[y * 64 + x] = color
            }
        }
    }
}
