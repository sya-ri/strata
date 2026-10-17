package dev.s7a.strata.performance

import com.google.gson.JsonObject
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * External collection remains usable without runtime hooks and never substitutes zero for unavailable work.
 */
class UninstrumentedRuntimeMeasurementTest {
    @Test
    fun workWindowExecutesRealOperationsWithoutCreatingRuntimeCounters() {
        val owner = UninstrumentedOwner()
        val monitor = RuntimeWorkMonitor(owner, checkpointSamples = 2)
        repeat(5) { monitor.sample { owner.operations += 1 } }
        assertEquals(5, owner.operations)
        assertFalse(monitor.available)
        val report = monitor.snapshot()
        assertFalse(report.get("available").asBoolean)
        assertTrue(report.get("counts").isJsonNull)
        assertFailsWith<IllegalStateException> { monitor.verify(WorkExpectation(exact = mapOf("Paint" to 0L))) }
        monitor.close()
        monitor.close()
        assertFailsWith<IllegalStateException> { monitor.sample { owner.operations += 1 } }
        assertEquals(5, owner.operations)
    }

    @Test
    fun accumulationRetainsSampleCountWhileWorkRemainsUnavailable() {
        val owner = UninstrumentedOwner()
        RuntimeWorkAccumulator(owner).use { accumulator ->
            repeat(3) {
                owner.operations += 1
                accumulator.capture()
            }
            val report = accumulator.snapshot()
            assertEquals(3, owner.operations)
            assertEquals(3L, report.get("diagnostic_samples").asLong)
            assertFalse(report.get("available").asBoolean)
            assertTrue(report.get("counts").isJsonNull)
            assertTrue(report.get("active_subscriptions").isJsonNull)
        }
    }

    @Test
    fun nativeReportsKeepActualCommandsAndMarkAbsentCountsAndBytesUnavailable() {
        val owner = UninstrumentedOwner()
        val baseline = NativePresentationCounters.read(owner)
        assertTrue(baseline.isEmpty())
        val report = JsonObject()
        NativePresentationCounters.append(report, owner, baseline)
        assertEquals(1, report.get("draw_commands").asInt)
        assertFalse(report.getAsJsonObject("native_counter_availability").get("available").asBoolean)
        assertTrue(report.getAsJsonObject("native_counter_delta").get("textureUploadCount").isJsonNull)
        assertTrue(report.getAsJsonObject("native_retained").get("sampledImageRetainedByteCount").isJsonNull)
        assertFalse(report.getAsJsonObject("native_payload").get("available").asBoolean)
        assertTrue(report.getAsJsonObject("native_payload").get("sourceUploadByteCount").isJsonNull)
    }

    @Test
    fun completedFramesAreObservedThroughFabricEventsAndStopAfterClose() {
        val screen = UninstrumentedOwner()
        try {
            val observer = FabricFrameObserver(screen)
            ScreenEvents.beforeExtract(screen).fire()
            assertEquals(0L, observer.frames)
            repeat(3) { ScreenEvents.afterExtract(screen).fire() }
            assertEquals(3L, observer.frames)
            observer.close()
            ScreenEvents.afterExtract(screen).fire()
            assertEquals(3L, observer.frames)
        } finally {
            ScreenEvents.release(screen)
        }
    }

    /**
     * Test-owned operation and display-list state, with no monitoring capability or metrics fields.
     */
    private class UninstrumentedOwner {
        var operations = 0
        val preparedCommands: List<String> = listOf("fixture command")
    }
}
