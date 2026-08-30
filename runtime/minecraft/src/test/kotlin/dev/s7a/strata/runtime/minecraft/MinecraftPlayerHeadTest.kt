package dev.s7a.strata.runtime.minecraft

import dev.s7a.strata.component.PlayerHead
import dev.s7a.strata.component.PlayerHeadScale
import dev.s7a.strata.component.PlayerSkinSource
import dev.s7a.strata.geometry.Constraints
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.modifier.Modifier
import dev.s7a.strata.render.DrawImage
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.UiTree
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
 * Verifies the reusable player-head component against Minecraft 26.2 face-layer geometry.
 */
@OptIn(InternalStrataRuntimeApi::class)
internal class MinecraftPlayerHeadTest {
    @Test
    fun faceAndHatUseNativeSourcesAndOrder() {
        val skin = testSkin()
        val host = scaledHost(skin, showHat = true, scale = PlayerHeadScale(3))
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
        val host = legacyHost(skin, showHat = false, size = 12)
        try {
            host.attach()
            val command = host.frame(IntSize(12, 12)).drawCommands.single() as DrawCommand.BlitImage
            assertNotSame(skin, command.image)
            assertEquals(IntSize(12, 12), command.image.size)
            assertEquals(IntRect(0, 0, 12, 12), command.source)
            assertEquals(IntRect(0, 0, 12, 12), command.destination)
            assertEquals(0xFFFF0000.toInt(), rasterizeHeadless(listOf(command), IntSize(12, 12)).argbAt(6, 6))
        } finally {
            host.close()
        }
    }

    @Test
    fun arbitraryLegacySizeUsesRegionClampedPremultipliedBilinearSampling() {
        val skin = splitFaceSkin()
        val host = legacyHost(skin, showHat = false, size = 10)
        try {
            host.attach()
            val command = host.frame(IntSize(10, 10)).drawCommands.single() as DrawCommand.BlitImage
            val reused = host.frame(IntSize(10, 10)).drawCommands.single() as DrawCommand.BlitImage
            assertSame(command.image, reused.image)
            val rendered = rasterizeHeadless(listOf(command), IntSize(10, 10))
            assertEquals(0xFFFF0000.toInt(), rendered.argbAt(0, 0))
            assertEquals(0xFFE6001A.toInt(), rendered.argbAt(4, 0))
            assertEquals(0xFF1A00E6.toInt(), rendered.argbAt(5, 0))
            assertEquals(0xFF0000FF.toInt(), rendered.argbAt(9, 0))
        } finally {
            host.close()
        }
    }

    @Test
    fun filteredAlphaInterpolatesPremultipliedChannelsWithoutTransparentColorBleed() {
        val skin = transparentSplitFaceSkin()
        val host = legacyHost(skin, showHat = false, size = 10)
        try {
            host.attach()
            val command = host.frame(IntSize(10, 10)).drawCommands.single() as DrawCommand.BlitImage
            assertEquals(0x00000000, command.image.argbAt(0, 0))
            assertEquals(0x1AFF0000, command.image.argbAt(4, 0))
            assertEquals(0xE6FF0000.toInt(), command.image.argbAt(5, 0))
            assertEquals(0xFFFF0000.toInt(), command.image.argbAt(9, 0))
        } finally {
            host.close()
        }
    }

    @Test
    fun filteredCacheIsReplacedForEqualPixelsWithANewSkinIdentityOrSize() {
        val firstSkin = testSkin()
        val equalSkin = testSkin()
        assertEquals(firstSkin, equalSkin)
        assertNotSame(firstSkin, equalSkin)
        UiTree().use { tree ->
            tree.update(createMinecraftPlayerHeadElement(firstSkin, 10, false, Modifier.Empty, null))
            tree.measure(Constraints.fixed(10, 10))
            tree.layout()
            val first = tree.paint().single() as DrawCommand.BlitImage

            tree.update(createMinecraftPlayerHeadElement(equalSkin, 10, false, Modifier.Empty, null))
            val replacedSkin = tree.paint().single() as DrawCommand.BlitImage
            assertNotSame(first.image, replacedSkin.image)

            tree.update(createMinecraftPlayerHeadElement(equalSkin, 11, false, Modifier.Empty, null))
            tree.measure(Constraints.fixed(11, 11))
            tree.layout()
            val replacedSize = tree.paint().single() as DrawCommand.BlitImage
            assertNotSame(replacedSkin.image, replacedSize.image)
            assertEquals(IntSize(11, 11), replacedSize.image.size)
        }
    }

    @Test
    fun sourceSizeLogicalSizeAndConstraintsAreValidated() {
        val wrongSkin = createDrawImage(IntSize(63, 64), IntArray(63 * 64))
        val wrongSource = scaledHost(wrongSkin, showHat = true, scale = PlayerHeadScale(3))
        try {
            assertThrows(IllegalArgumentException::class.java) { wrongSource.attach() }
        } finally {
            wrongSource.close()
        }
        val wrongSize = legacyHost(testSkin(), showHat = true, size = 0)
        try {
            assertThrows(IllegalArgumentException::class.java) { wrongSize.attach() }
        } finally {
            wrongSize.close()
        }
        val oversizedFiltered = legacyHost(testSkin(), showHat = true, size = 1025)
        try {
            assertThrows(IllegalArgumentException::class.java) { oversizedFiltered.attach() }
        } finally {
            oversizedFiltered.close()
        }

        val host = scaledHost(testSkin(), showHat = true, scale = PlayerHeadScale(3))
        try {
            host.attach()
            assertThrows(IllegalArgumentException::class.java) { host.frame(IntSize(23, 24)) }
        } finally {
            host.close()
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyHost(
        skin: DrawImage,
        showHat: Boolean,
        size: Int,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Player head") { PlayerHead(PlayerSkinSource.Pixels(skin), size, showHat) },
            MinecraftProfileFixture.create(),
        )

    private fun scaledHost(
        skin: DrawImage,
        showHat: Boolean,
        scale: PlayerHeadScale,
    ): MinecraftUiHost =
        createMinecraftUiHost(
            ScreenDefinition("Player head") { PlayerHead(PlayerSkinSource.Pixels(skin), scale, showHat) },
            MinecraftProfileFixture.create(),
        )

    private fun testSkin(): DrawImage {
        val pixels = IntArray(64 * 64)
        fill(pixels, IntRect(8, 8, 16, 16), 0xFFFF0000.toInt())
        fill(pixels, IntRect(40, 8, 48, 16), 0x800000FF.toInt())
        return createDrawImage(IntSize(64, 64), pixels)
    }

    private fun splitFaceSkin(): DrawImage {
        val pixels = IntArray(64 * 64) { 0xFF00FF00.toInt() }
        fill(pixels, IntRect(8, 8, 12, 16), 0xFFFF0000.toInt())
        fill(pixels, IntRect(12, 8, 16, 16), 0xFF0000FF.toInt())
        return createDrawImage(IntSize(64, 64), pixels)
    }

    private fun transparentSplitFaceSkin(): DrawImage {
        val pixels = IntArray(64 * 64) { 0xFF0000FF.toInt() }
        fill(pixels, IntRect(8, 8, 12, 16), 0x0000FF00)
        fill(pixels, IntRect(12, 8, 16, 16), 0xFFFF0000.toInt())
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
