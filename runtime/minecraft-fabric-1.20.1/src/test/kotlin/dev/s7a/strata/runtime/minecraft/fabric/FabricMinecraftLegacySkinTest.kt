package dev.s7a.strata.runtime.minecraft.fabric

import com.mojang.blaze3d.platform.NativeImage
import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Verifies legacy skin expansion, alpha rules, detached pixels, and decoded-image release without a loaded client.
 */
internal class FabricMinecraftLegacySkinTest {
    @Test
    fun legacySkinMirrorsLimbsAndClearsAnOpaqueHatBeforeClosingNativePixels() {
        NativeImage(64, 32, true).use { image ->
            image.fillRect(0, 0, 64, 32, 0xFFFFFFFF.toInt())
            setFabricMinecraftArgbPixel(image, 4, 16, 0xFF123456.toInt())
            val snapshot = snapshotLegacyFabricMinecraftSkin(image)
            assertEquals(IntSize(64, 64), snapshot.size)
            assertEquals(0xFF123456.toInt(), snapshot.argbAt(23, 48))
            assertEquals(0x00FFFFFF, snapshot.argbAt(40, 8))
            assertEquals(0xFFFFFFFF.toInt(), snapshot.argbAt(8, 8))
            assertThrows(IllegalStateException::class.java) { image.getPixelRGBA(0, 0) }
        }
    }

    @Test
    fun modernSkinPreservesHatAlphaAndClosesRejectedDimensions() {
        NativeImage(64, 64, true).use { image ->
            setFabricMinecraftArgbPixel(image, 40, 8, 0x80123456.toInt())
            val snapshot = snapshotLegacyFabricMinecraftSkin(image)
            assertEquals(0x80123456.toInt(), snapshot.argbAt(40, 8))
            assertThrows(IllegalStateException::class.java) { image.getPixelRGBA(0, 0) }
        }
        NativeImage(63, 32, true).use { image ->
            assertThrows(IllegalArgumentException::class.java) { snapshotLegacyFabricMinecraftSkin(image) }
            assertThrows(IllegalStateException::class.java) { image.getPixelRGBA(0, 0) }
        }
    }
}
