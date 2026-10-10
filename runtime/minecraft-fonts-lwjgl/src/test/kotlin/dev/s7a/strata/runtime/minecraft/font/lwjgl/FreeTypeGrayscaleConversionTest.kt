package dev.s7a.strata.runtime.minecraft.font.lwjgl

import dev.s7a.strata.runtime.minecraft.font.MinecraftTrueTypeRasterizer
import org.junit.jupiter.api.Test

/**
 * Checks the actual private converter against independent logical pixels and scalar native byte addresses.
 * Native fixture symbols stay outside this test class so STB-only workers can discover its tests.
 */
internal class FreeTypeGrayscaleConversionTest {
    @Test
    fun completeFrozenExtentAndPitchMatrixPreservesEveryPixelAfterRelease() = withFreeType { FreeTypeGrayscaleFixture.matrix(it) }

    @Test
    fun asymmetricRowsExcludePoisonedPaddingAndCoverEveryUnsignedIntensity() = withFreeType { FreeTypeGrayscaleFixture.asymmetric(it) }

    @Test
    fun grayscaleExtentAndStrideRejectionsPreserveTheirOriginalOrder() = withFreeType { FreeTypeGrayscaleFixture.rejections(it) }

    @Test
    fun checkedBufferSizePrecedesMissingPixelsAndOutputAllocation() = withFreeType { FreeTypeGrayscaleFixture.bufferFailures(it) }

    @Test
    fun emptyAndNegativePrivateExtentsPreserveOriginalOutcomes() = withFreeType { FreeTypeGrayscaleFixture.privateExtents(it) }

    private fun withFreeType(verify: (ByteArray) -> Unit) {
        val selected = System.getProperty("strata.fontRasterizer")?.let { listOf(MinecraftTrueTypeRasterizer.valueOf(it)) } ?: MinecraftTrueTypeRasterizer.entries
        if (MinecraftTrueTypeRasterizer.FreeType in selected) {
            verify(checkNotNull(javaClass.getResourceAsStream("/fonts/strata-test.ttf")).use { it.readBytes() })
        }
    }
}
