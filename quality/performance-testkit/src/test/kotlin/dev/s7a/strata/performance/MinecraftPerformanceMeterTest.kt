package dev.s7a.strata.performance

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Host-independent callback orchestration regressions; loaded-host compatibility requires separate GameTests.
 */
class MinecraftPerformanceMeterTest {
    @Test
    fun captureAndSettlingAreOutsideCompleteSamplesAndReleaseMonitoring() {
        val screen = NativeMeterFixture()
        val order = mutableListOf<String>()
        MinecraftPerformanceMeter(screen).use { meter ->
            try {
                meter.begin(
                    "fixture",
                    PerformancePlan(warmup = 1, samples = 2),
                    fixture =
                        NativePerformanceFixture(
                            captureAfterWarmup = { order.add("capture") },
                            settleFrames = 2,
                            beforeSamples = { order.add("baseline") },
                            afterSamples = { order.add("complete") },
                        ),
                    update = { order.add("update") },
                )
                repeat(5) {
                    ScreenEvents.beforeExtract(screen).fire()
                    screen.renderExtractionCount += 1
                    screen.hostFrameCount += 1
                    ScreenEvents.afterExtract(screen).fire()
                }
                assertTrue(meter.completed)
                assertTrue(screen.monitorClosed)
                assertEquals(listOf("update", "capture", "baseline", "update", "update", "complete"), order)
                val evidence = meter.result()
                assertEquals(2, evidence.get("samples").asInt)
                assertEquals(2, evidence.getAsJsonObject("native_counter_delta").get("renderExtractionCount").asLong)
                assertEquals(2, evidence.getAsJsonObject("frame_interval").get("samples").asInt)
                evidence.addProperty("samples", 99)
                assertEquals(2, meter.result().get("samples").asInt)
            } finally {
                ScreenEvents.release(screen)
            }
        }
    }

    @Test
    fun minimizedWindowFailsBeforeAnyApplicationActionOrTiming() {
        val screen = NativeMeterFixture()
        var invoked = false
        MinecraftPerformanceMeter(screen).use { meter ->
            try {
                meter.begin("minimized", PerformancePlan(warmup = 0, samples = 1), update = { invoked = true })
                Minecraft.getInstance().getWindow().iconified = true
                ScreenEvents.beforeExtract(screen).fire()
                assertFailsWith<IllegalStateException> { meter.completed }
                assertFalse(invoked)
                assertFalse(meter.receipt().has("measurement"))
                assertFalse(meter.receipt().get("complete").asBoolean)
            } finally {
                Minecraft.getInstance().getWindow().iconified = false
                ScreenEvents.release(screen)
            }
        }
    }

    @Test
    fun callbackFailurePropagatesAndCannotPublishPartialTiming() {
        val screen = NativeMeterFixture()
        val failure = IllegalArgumentException("fixture action")
        MinecraftPerformanceMeter(screen).use { meter ->
            try {
                meter.begin("failure", PerformancePlan(warmup = 0, samples = 2), update = { throw failure })
                ScreenEvents.beforeExtract(screen).fire()
                assertEquals(failure, assertFailsWith<IllegalArgumentException> { meter.result() })
                assertEquals(failure, assertFailsWith<IllegalArgumentException> { meter.completed })
                assertTrue(screen.monitorClosed)
                assertFalse(meter.receipt().get("complete").asBoolean)
                assertFalse(meter.receipt().has("measurement"))
            } finally {
                ScreenEvents.release(screen)
            }
        }
    }
}
