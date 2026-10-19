package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks actual completion requirements, device tick conversion, bounded recording and reader release without a GPU.
 */
internal class GpuPerformanceMeterTest {
    @Test
    fun incompleteQueriesCannotProduceDistributionsAndDevicePeriodControlsUnits() {
        val values = arrayOf<Long?>(100, null, 1_000, 1_050)
        val meter = GpuPerformanceMeter(2, 2.0, "GUI commands") { values[it] }
        assertFalse(meter.completed)
        meter.begin()
        meter.recorded()
        meter.begin()
        meter.recorded()
        assertFalse(meter.completed)
        assertFailsWith<IllegalStateException> { meter.result() }
        values[1] = 120
        assertTrue(meter.completed)
        val report = meter.result()
        assertEquals(2.0, report.get("timestamp_period_ns").asDouble)
        assertEquals(listOf(40L, 100L), report.getAsJsonObject("duration").getAsJsonArray("raw").map { it.asLong })
        report.addProperty("samples", 99)
        assertEquals(2, meter.result().get("samples").asInt)
        assertFailsWith<IllegalStateException> { meter.recorded() }
        meter.close()
        assertFailsWith<IllegalStateException> { meter.completed }
    }

    @Test
    fun invalidDifferencesAndTickPeriodsFailInsteadOfReportingZeroWork() {
        for (period in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { GpuPerformanceMeter(1, period, "GUI") { 0 } }
        }
        for (pair in listOf(10L to 5L, Long.MIN_VALUE to Long.MAX_VALUE)) {
            GpuPerformanceMeter(1, 1.0, "GUI") { if (it == 0) pair.first else pair.second }.use { meter ->
                meter.begin()
                meter.recorded()
                assertFailsWith<RuntimeException> { meter.result() }
            }
        }
        assertFailsWith<IllegalArgumentException> { GpuPerformanceMeter(10_001, 1.0, "GUI") { 0 } }
        assertFailsWith<IllegalArgumentException> { GpuPerformanceMeter(1, 1.0, "") { 0 } }
    }
}
