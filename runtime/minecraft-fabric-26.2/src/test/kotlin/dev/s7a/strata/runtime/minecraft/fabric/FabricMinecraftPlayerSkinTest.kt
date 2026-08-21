package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies detached normalized player-skin snapshots without requiring a loaded client.
 */
internal class FabricMinecraftPlayerSkinTest {
    @Test
    fun nativeArgbPixelsAreCopiedAndDetached() {
        NativeImage(64, 64, false).use { image ->
            image.setPixel(8, 8, 0xFF123456.toInt())
            val snapshot = createPlayerSkinSnapshot(image)
            assertEquals(0xFF123456.toInt(), snapshot.argbAt(8, 8))
            image.setPixel(8, 8, 0xFFABCDEF.toInt())
            assertEquals(0xFF123456.toInt(), snapshot.argbAt(8, 8))
        }
    }

    @Test
    fun onlyNormalizedSixtyFourPixelSkinsAreAccepted() {
        NativeImage(64, 32, false).use { image ->
            assertThrows(IllegalArgumentException::class.java) { createPlayerSkinSnapshot(image) }
        }
        NativeImage(63, 64, false).use { image ->
            assertThrows(IllegalArgumentException::class.java) { createPlayerSkinSnapshot(image) }
        }
    }
}
