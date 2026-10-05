@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.runtime.minecraft.fabric

import dev.s7a.strata.geometry.FloatRect
import dev.s7a.strata.geometry.IntRect
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.render.createDrawImage
import dev.s7a.strata.runtime.render.DrawCommand
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Checks image-count and aggregate GPU byte limits before allocation, including exact CPU fallback accounting.
 */
internal class FabricMinecraftSamplingBudgetTest {
    @Test
    fun lookupBytesCountTowardTheLimitAndRejectedAllocationConsumesNothing() {
        val budget = FabricMinecraftSamplingBudget()
        val clip = IntRect(0, 0, 4096, 4096)
        assertFalse(budget.admit(FloatRect(0f, 0f, 4096f, 4096f), clip, 1))
        assertTrue(budget.admit(FloatRect(0f, 0f, 4096f, 4093f), clip, 1))
        assertFalse(budget.admit(FloatRect(0f, 0f, 1f, 1f), clip, 1))
        assertTrue(budget.admit(FloatRect(5000f, 5000f, 5001f, 5001f), clip, 1))
    }

    @Test
    fun excessExactOutputsFallBackWithoutChangingUnsupportedOrSourceCapacityCounts() {
        val image = createDrawImage(IntSize(2, 2), IntArray(4) { -1 })
        val command = DrawCommand.SampledImage(image, FloatRect(0f, 0f, 2f, 2f), FloatRect(0f, 0f, 1f, 1f), alphaCutoff = 0f)
        val inputs = FabricMinecraftFrameInputs(partitionFabricMinecraftFrame(List(257) { command }, IntSize(1, 1), exactSampling = true), 1)
        assertEquals(256, inputs.sampled.size)
        assertEquals(1L, inputs.capacitySampledImages)
        assertEquals(0L, inputs.ineligibleSampledImages)
        val resolved = inputs.resolve({ false }) { true }
        assertEquals(257L, resolved.capacitySampledImages)
        assertEquals(0L, resolved.ineligibleSampledImages)
    }
}
