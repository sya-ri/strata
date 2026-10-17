package dev.s7a.strata.runtime.minecraft.fabric

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Production presentation owns rendering and lifetime state without diagnostic counters or notification hooks.
 */
internal class ProductionPresentationBoundaryTest {
    @Test
    fun sourceBorrowHasNoCounterCallbacks() {
        val borrow = FabricMinecraftSampledImageDevice::class.java.declaredMethods.single { it.name.startsWith("borrow") }
        assertEquals(2, borrow.parameterCount)
    }

    @Test
    fun preparedFramesAndScreensDoNotRetainMeasurementState() {
        val forbidden = setOf("cpuLayers", "capacitySampledImages", "ineligibleSampledImages", "tintFallbackImages", "alphaCutoffFallbackImages")
        assertFalse(FabricMinecraftFrameInputs::class.java.declaredFields.any { it.name in forbidden })
        val counterNames = setOf("renderExtractionCount", "hostFrameCount", "framePreparationCount", "textureUploadCount", "uploadWork")
        assertFalse(FabricMinecraftScreen::class.java.declaredFields.any { it.name in counterNames })
        assertNull(FabricMinecraftFrameInputs::class.java.getResource("FabricMinecraftUploadWork.class"))
    }
}
