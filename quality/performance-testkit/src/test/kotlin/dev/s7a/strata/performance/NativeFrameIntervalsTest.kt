package dev.s7a.strata.performance

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Tests whole-frame boundaries with supplied readings, without delays or wall-time thresholds.
 */
class NativeFrameIntervalsTest {
    @Test
    fun preparationGapsAreExcludedAndTheFinalSpanIsRequired() {
        val frames = NativeFrameIntervals(2)
        frames.start(100, 20)
        frames.complete(140, 30)
        assertFails { frames.appendTo(JsonObject()) }
        frames.start(1000, 500)
        assertFails { frames.appendTo(JsonObject()) }
        frames.complete(1050, 520)
        val result = JsonObject().also(frames::appendTo)
        assertEquals(listOf(40L, 50L), result.getAsJsonObject("frame_interval").getAsJsonArray("raw").map { it.asLong })
        assertEquals(listOf(10L, 20L), result.getAsJsonObject("render_thread_frame_cpu").getAsJsonArray("raw").map { it.asLong })
        assertFails { frames.start(1100, 530) }
        assertFails { frames.complete(1100, 530) }
    }

    @Test
    fun missingCpuRemainsUnavailableAndNanoTimeWrapPreservesElapsed() {
        val frames = NativeFrameIntervals(2)
        frames.start(Long.MAX_VALUE - 20, 100)
        frames.complete(Long.MIN_VALUE + 19, 105)
        frames.start(Long.MIN_VALUE + 100, null)
        frames.complete(Long.MIN_VALUE + 150, 110)
        val result = JsonObject().also(frames::appendTo)
        assertEquals(listOf(40L, 50L), result.getAsJsonObject("frame_interval").getAsJsonArray("raw").map { it.asLong })
        assertTrue(result.get("render_thread_frame_cpu").isJsonNull)
    }

    @Test
    fun unpairedOrBackwardReadingsCannotProduceEvidence() {
        assertFails { NativeFrameIntervals(0) }
        val frames = NativeFrameIntervals(1)
        assertFails { frames.complete(10, 10) }
        frames.start(10, 10)
        assertFails { frames.start(20, 20) }
        assertFails { frames.complete(9, 20) }
        assertFails { frames.complete(20, 9) }
        frames.complete(20, 20)
        assertEquals(
            1,
            JsonObject()
                .also(frames::appendTo)
                .getAsJsonObject("frame_interval")
                .get("samples")
                .asInt,
        )
    }
}
