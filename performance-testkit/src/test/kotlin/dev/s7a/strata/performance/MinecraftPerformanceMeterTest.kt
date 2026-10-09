package dev.s7a.strata.performance

import com.google.gson.JsonObject
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
    fun payloadDeltasRemainSeparateAndOlderRuntimeMetricsRemainUnavailable() {
        val screen = NativeMeterFixture()
        val unavailable = JsonObject()
        NativePresentationCounters.append(unavailable, screen, NativePresentationCounters.read(screen))
        assertFalse(unavailable.getAsJsonObject("native_payload").get("available").asBoolean)
        assertFalse(unavailable.getAsJsonObject("native_gpu").get("available").asBoolean)
        val payload = NativeMeterFixture.UploadWork()
        screen.uploadWork = payload
        payload.sourceUploadByteCount = 40L
        val baseline = NativePresentationCounters.read(screen)
        payload.sourceUploadByteCount += 24L
        payload.rasterUploadByteCount += 320L
        payload.samplingUploadByteCount += 96L
        payload.tintFallbackCount += 2L
        payload.otherIneligibleFallbackCount += 1L
        val report = JsonObject()
        NativePresentationCounters.append(report, screen, baseline)
        val evidence = report.getAsJsonObject("native_payload")
        assertTrue(evidence.get("available").asBoolean)
        assertEquals(24L, evidence.get("sourceUploadByteCount").asLong)
        assertEquals(320L, evidence.get("rasterUploadByteCount").asLong)
        assertEquals(96L, evidence.get("samplingUploadByteCount").asLong)
        assertEquals(2L, evidence.get("tintFallbackCount").asLong)
        assertEquals(0L, evidence.get("alphaCutoffFallbackCount").asLong)
        assertEquals(1L, evidence.get("otherIneligibleFallbackCount").asLong)
    }

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
                assertFalse(meter.completed)
                assertFalse(meter.receipt().get("complete").asBoolean)
                assertTrue(meter.receipt().get("incomplete_frame").asBoolean)
                ScreenEvents.beforeExtract(screen).fire()
                assertTrue(meter.completed)
                assertFalse(meter.receipt().get("incomplete_frame").asBoolean)
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
    fun settledSamplesValidateTheirInitialAndEveryCompleteFrameBoundaryExactlyOnce() {
        val screen = NativeMeterFixture()
        val plan = PerformanceProfile.Standard.plan()
        val settleFrames = 8
        var baseline: Long? = null
        val completedFrameBoundaries = mutableListOf<Long>()
        MinecraftPerformanceMeter(screen).use { meter ->
            try {
                meter.begin(
                    "boundary-order",
                    plan,
                    fixture =
                        NativePerformanceFixture(
                            settleFrames = settleFrames,
                            beforeSamples = { baseline = screen.renderExtractionCount },
                            validateFrame = { baseline?.let { completedFrameBoundaries.add(screen.renderExtractionCount - it) } },
                            afterSamples = { assertEquals((0..plan.samples).map(Int::toLong), completedFrameBoundaries) },
                        ),
                )
                repeat(plan.warmup + settleFrames + plan.samples) {
                    ScreenEvents.beforeExtract(screen).fire()
                    screen.renderExtractionCount += 1
                    screen.hostFrameCount += 1
                    ScreenEvents.afterExtract(screen).fire()
                }
                assertFalse(meter.completed)
                assertEquals(plan.samples, completedFrameBoundaries.size)
                ScreenEvents.beforeExtract(screen).fire()
                assertTrue(meter.completed)
                assertEquals((0..plan.samples).map(Int::toLong), completedFrameBoundaries)
                val report = meter.result()
                assertEquals(plan.samples, report.get("samples").asInt)
                assertEquals(plan.samples, report.getAsJsonObject("frame_interval").get("samples").asInt)
                assertTrue(screen.monitorClosed)
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
                assertTrue(meter.receipt().get("incomplete_frame").asBoolean)
                assertFalse(meter.receipt().has("measurement"))
            } finally {
                ScreenEvents.release(screen)
            }
        }
    }
}
