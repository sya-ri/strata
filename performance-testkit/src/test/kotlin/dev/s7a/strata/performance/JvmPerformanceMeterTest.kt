package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import javax.management.ObjectName
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the shared collection and evidence contract.
 */
class JvmPerformanceMeterTest {
    @Test
    fun aggregateAllocationPreservesUnavailableOrIncludesTerminatedThreads() {
        val meter = JvmPerformanceMeter("aggregate", 1)
        val aggregateAvailable =
            runCatching {
                val value = ManagementFactory.getPlatformMBeanServer().getAttribute(ObjectName("java.lang:type=Threading"), "TotalThreadAllocatedBytes")
                value is Long && 0 <= value
            }.getOrDefault(false)
        var retained = ByteArray(0)
        val worker = Thread { retained = ByteArray(8 * 1024 * 1024) }
        meter.sample {
            worker.start()
            worker.join()
        }
        assertEquals(8 * 1024 * 1024, retained.size)
        val aggregate = meter.result().get("all_threads_allocated_bytes")
        if (aggregateAvailable) {
            assertTrue(retained.size.toLong() <= aggregate.asLong)
        } else {
            assertTrue(aggregate.isJsonNull)
        }
    }

    @Test
    fun requiresAllSuccessfulSamplesAndDetachedEvidence() {
        val meter = JvmPerformanceMeter("bounded", 2)
        meter.sample { ByteArray(128).fill(1) }
        assertFailsWith<IllegalStateException> { meter.result() }
        meter.sample { ByteArray(256).fill(2) }
        val report = meter.result()
        assertEquals(2, report.get("samples").asInt)
        assertTrue(0 <= report.get("wall_total_ns").asLong)
        assertTrue(report.has("owner_thread_allocated_bytes"))
        report.addProperty("samples", 99)
        assertEquals(2, meter.result().get("samples").asInt)
        assertFailsWith<IllegalStateException> { meter.begin() }
    }

    @Test
    fun operationFailureCannotPublishEvidence() {
        val meter = JvmPerformanceMeter("failure", 1)
        assertFailsWith<IllegalArgumentException> { meter.sample { errorArgument() } }
        assertFailsWith<IllegalStateException> { meter.result() }
        assertFailsWith<IllegalStateException> { meter.begin() }
    }

    @Test
    fun runnerKeepsPreparationAndChecksOutsideSampleAndStopsOnFailure() {
        val order = mutableListOf<String>()
        val run =
            JvmPerformanceRunner.measure(
                "order",
                PerformancePlan(warmup = 1, samples = 2),
                beforeSample = { order.add("prepare:$it") },
                afterSample = { order.add("check:$it") },
            ) { index ->
                order.add("sample:$index")
                index
            }
        assertEquals(listOf("sample:0", "prepare:0", "sample:0", "check:0", "prepare:1", "sample:1", "check:1"), order)
        assertEquals(1, run.value)
        assertEquals(2, run.evidence.get("samples").asInt)
        val failure = IllegalArgumentException("fixture")
        var checks = 0
        assertEquals(
            failure,
            assertFailsWith<IllegalArgumentException> {
                JvmPerformanceRunner.measure("failed", PerformancePlan(warmup = 0, samples = 2), afterSample = { checks += 1 }) {
                    throw failure
                }
            },
        )
        assertEquals(0, checks)
    }

    @Test
    fun interleavedWorkloadsRejectPartialEvidenceAndReleaseCollectors() {
        var retained: JvmPerformanceSequence? = null
        val reports =
            JvmPerformanceRunner.sequence(2, mapOf("cold" to 2, "warm" to 2)) { _, sequence ->
                retained = sequence
                sequence.sample("cold") {}
                sequence.sample("warm") {}
            }
        assertEquals(setOf("cold", "warm"), reports.keys)
        assertEquals(2, reports.getValue("cold").get("samples").asInt)
        assertFailsWith<IllegalStateException> { checkNotNull(retained).sample("cold") {} }
        assertFailsWith<IllegalStateException> {
            JvmPerformanceRunner.sequence(1, mapOf("missing" to 2)) { _, sequence -> sequence.sample("missing") {} }
        }
    }

    private fun errorArgument(): Unit = throw IllegalArgumentException("expected")
}
