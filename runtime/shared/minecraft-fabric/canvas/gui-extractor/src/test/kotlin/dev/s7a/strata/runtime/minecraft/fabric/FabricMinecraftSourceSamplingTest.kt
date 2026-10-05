package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Verifies bounded fractional-source admission independently of native textures. */
internal class FabricMinecraftSourceSamplingTest {
    private val image = createDrawImage(IntSize(2, 2), intArrayOf(-1, 0, 0x804466AA.toInt(), -1))
    private val crop = DrawCommand.SampledImage(image, FloatRect(0.25f, 0.25f, 1.75f, 1.75f), FloatRect(0f, 0f, 5f, 5f), alphaCutoff = 0f)

    @Test
    fun sourceTexelBoundaryUsesFallbackWhileEvenDensityKeepsDirectSampling() {
        for (scale in 1..4) assertEquals(scale == 2 || scale == 4, isDirectFabricSampledImage(crop, scale, fractionalSource = true))
        val close = crop.copy(source = FloatRect(0.25f, 0.25f, Math.nextUp(1.75f), 1.75f))
        assertFalse(isDirectFabricSampledImage(close, 1, fractionalSource = true))
        val mixed = crop.copy(image = createDrawImage(IntSize(2, 4), IntArray(8) { -1 }), source = FloatRect(0.5f, 0f, 1.5f, 4f))
        for (scale in 1..4) assertEquals(scale == 4, isDirectFabricSampledImage(mixed, scale, fractionalSource = true))
    }

    @Test
    fun safeFractionalSamplesPreserveDirectAdmissionAtEveryDensity() {
        val safe = crop.copy(source = FloatRect(0.25f, 0.5f, 1.75f, 2f), destination = FloatRect(1f, 1f, 5f, 5f))
        for (scale in 1..4) assertTrue(isDirectFabricSampledImage(safe, scale, fractionalSource = true))
        val integer = crop.copy(source = FloatRect(0f, 0f, 2f, 2f), destination = FloatRect(1f, 1f, 2f, 2f))
        assertTrue(isDirectFabricSampledImage(integer))
        val shifted = safe.copy(destination = FloatRect(0.25f, 0.25f, 4.25f, 4.25f))
        for (scale in 1..4) assertEquals(scale == 4, isDirectFabricSampledImage(shifted, scale, fractionalSource = true))
    }

    @Test
    fun oversizedAndUnrepresentableAxesFallBackWithoutUnboundedPreparation() {
        assertFalse(isDirectFabricSampledImage(crop.copy(destination = FloatRect(0f, 0f, 4097f, 5f)), fractionalSource = true))
        assertFalse(isDirectFabricSampledImage(crop.copy(destination = FloatRect(1_048_576f, 0f, 1_048_580f, 5f)), fractionalSource = true))
        assertFalse(isDirectFabricSampledImage(crop.copy(destination = FloatRect(-Float.MAX_VALUE / 2f, 0f, Float.MAX_VALUE / 2f, 5f)), fractionalSource = true))
    }
}
