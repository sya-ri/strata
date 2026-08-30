package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Verifies the native adapter's explicit same-lease snapshot normalization independently of GPU execution. */
internal class FabricMinecraftCanvasSnapshotTest {
    @Test
    fun nativeExtentBoundProtectsIntegerPixelCenterSampling() {
        assertDoesNotThrow { FabricNativeCanvasShaders.requireSupportedExtent(IntSize(32768, 32768)) }
        listOf(IntSize(32769, 1), IntSize(1, 32769), IntSize(0, 1), IntSize(1, 0)).forEach { size ->
            assertThrows<IllegalArgumentException> { FabricNativeCanvasShaders.requireSupportedExtent(size) }
        }
    }

    @Test
    fun absentSnapshotRemainsAbsent() {
        assertNull(normalizeCanvasSnapshot(null, IntSize(3, 2), IntSize(2, 3), MinecraftCanvasTextureOrigin.TopLeft))
    }

    @Test
    fun matchingNormalizedSnapshotRetainsIdentity() {
        val image = createDrawImage(IntSize(2, 1), intArrayOf(0x80204060.toInt(), 0xFF123456.toInt()))
        assertSame(image, normalizeCanvasSnapshot(image, image.size, image.size, MinecraftCanvasTextureOrigin.TopLeft))
    }

    @Test
    fun samplingUsesDestinationPixelCenters() {
        val image = createDrawImage(IntSize(3, 2), intArrayOf(1, 2, 3, 4, 5, 6))
        val normalized = checkNotNull(normalizeCanvasSnapshot(image, image.size, IntSize(2, 3), MinecraftCanvasTextureOrigin.TopLeft))
        assertArrayEquals(intArrayOf(1, 3, 4, 6, 4, 6), normalized.copyArgb())
    }

    @Test
    fun bottomOriginIsNormalizedBeforeHeadlessCapture() {
        val image = createDrawImage(IntSize(3, 2), intArrayOf(1, 2, 3, 4, 5, 6))
        val normalized = checkNotNull(normalizeCanvasSnapshot(image, image.size, IntSize(2, 3), MinecraftCanvasTextureOrigin.BottomLeft))
        assertArrayEquals(intArrayOf(4, 6, 1, 3, 1, 3), normalized.copyArgb())
    }

    @Test
    fun snapshotExtentMismatchFailsBeforeNativeWork() {
        val image = createDrawImage(IntSize(1, 1), intArrayOf(0))
        assertThrows<IllegalArgumentException> {
            normalizeCanvasSnapshot(image, IntSize(2, 1), IntSize(4, 4), MinecraftCanvasTextureOrigin.TopLeft)
        }
    }
}
