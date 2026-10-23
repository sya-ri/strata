package dev.s7a.strata.performance

import com.google.gson.JsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exercises compiled hook admission and complete operation boundaries without starting JMH forks.
 */
internal class JvmOperationReportsTest {
    @Test
    fun failedOriginalWorkCannotStartSupplementalSampling() {
        val failure = IllegalStateException("Original work failed")
        var sampled = false
        val adapter =
            object : JvmOperationReportAdapter {
                override val rows: List<JvmOperationReportRow> = FirstAdapter.rows

                override fun collect(plan: PerformancePlan): Map<String, JsonObject> {
                    sampled = true
                    return FirstAdapter.collect(plan)
                }
            }
        assertSame(failure, assertFailsWith<IllegalStateException> { adapter.collectVerified(PerformancePlan(warmup = 0, samples = 2)) { throw failure } })
        assertEquals(false, sampled)
    }

    @Test
    fun requiredCompiledHooksResolveAndStaleMissingAmbiguousOrInvalidHooksFail() {
        val first = FirstFixture::class.java
        val second = SecondFixture::class.java
        val adapters = JvmOperationReports.operationAdapters(listOf(first, second), mapOf(first.name to FirstAdapter::class.java.name, second.name to SecondAdapter::class.java.name))
        assertEquals(listOf(FirstAdapter, SecondAdapter), adapters)
        assertFails { JvmOperationReports.operationAdapters(listOf(first), mapOf(first.name to SecondAdapter::class.java.name)) }
        listOf(MissingFixture::class.java, InvalidFixture::class.java, AmbiguousFixture::class.java).forEach { fixture ->
            assertFails { JvmOperationReports.operationAdapters(listOf(fixture), mapOf(fixture.name to FirstAdapter::class.java.name)) }
        }
        assertFails { JvmOperationReports.operationAdapters(listOf(first, first), mapOf(first.name to FirstAdapter::class.java.name)) }
        assertFails { JvmOperationReports.operationAdapters(listOf(first), emptyMap()) }
    }

    @Test
    fun rowsSamplesAndNullableCountersCannotBecomePartialOrZeroEvidence() {
        val rows = FirstAdapter.rows
        JvmOperationReports.validateRows(rows, 1, 0)
        assertFails { JvmOperationReports.validateRows(rows + rows, 2, 0) }
        assertFails { JvmOperationReports.validateRows(rows, 2, 0) }
        assertFails { JvmOperationReports.validateRows(rows, 1, 1) }
        val report = FirstAdapter.collect(PerformancePlan(warmup = 0, samples = 2)).getValue("first")
        JvmOperationReports.validateSample(report, 2)
        assertFails { JvmOperationReports.validateSample(report, 3) }
        report.add("owner_thread_cpu_distribution", null)
        report.add("owner_thread_allocation_distribution", null)
        report.add("owner_thread_cpu_ns", null)
        report.add("owner_thread_allocated_bytes", null)
        JvmOperationReports.validateSample(report, 2)
        assertTrue(report.get("owner_thread_cpu_ns").isJsonNull)
        report.remove("owner_thread_allocated_bytes")
        assertFails { JvmOperationReports.validateSample(report, 2) }
    }

    @Test
    fun primingAssertionsAndSurvivingTeardownAllocateOutsideTheDeclaredSample() {
        var sink: ByteArray? = null
        val report =
            JvmPerformanceRunner
                .sequence(2, mapOf("operation" to 2)) { _, sequence ->
                    sink = ByteArray(1024 * 1024)
                    sequence.sample("operation") { sink = ByteArray(64) }
                    assertEquals(64, checkNotNull(sink).size)
                    sink = ByteArray(1024 * 1024)
                }.getValue("operation")
        assertEquals(1024 * 1024, checkNotNull(sink).size)
        val allocated = report.get("owner_thread_allocated_bytes")
        if (allocated.isJsonNull.not()) assertTrue(allocated.asLong < 1024 * 1024)
    }

    @Test
    fun failedOperationReleasesTheRetainedSequenceAndPropagatesItsOriginalFailure() {
        var retained: JvmPerformanceSequence? = null
        val failure = IllegalArgumentException("Complete operation failed")
        assertSame(
            failure,
            assertFailsWith<IllegalArgumentException> {
                JvmPerformanceRunner.sequence(2, mapOf("first" to 2)) { _, sequence ->
                    retained = sequence
                    sequence.sample("first") { throw failure }
                }
            },
        )
        assertFailsWith<IllegalStateException> { checkNotNull(retained).sample("first") {} }
    }

    /**
     * First independent fixture-owned declaration.
     */
    object FirstFixture {
        /**
         * Returns the first adapter outside sampling.
         */
        @JvmStatic
        fun operationReport(): JvmOperationReportAdapter = FirstAdapter
    }

    /**
     * Second independent fixture-owned declaration.
     */
    object SecondFixture {
        /**
         * Returns the independently typed adapter.
         */
        @JvmStatic
        fun operationReport(): JvmOperationReportAdapter = SecondAdapter
    }

    /**
     * Represents an ordinary opted-out fixture.
     */
    class MissingFixture

    /**
     * Represents a hook with an invalid signature.
     */
    object InvalidFixture {
        /**
         * Cannot declare a report adapter.
         */
        @JvmStatic
        fun operationReport(): String = javaClass.name
    }

    /**
     * Represents ambiguous overloaded hook declarations.
     */
    object AmbiguousFixture {
        /**
         * Would be valid without its overload.
         */
        @JvmStatic
        fun operationReport(): JvmOperationReportAdapter = FirstAdapter

        /**
         * Conflicts with automatic discovery.
         */
        @JvmStatic
        fun operationReport(plan: PerformancePlan): JvmOperationReportAdapter = if (0 < plan.samples) FirstAdapter else SecondAdapter
    }

    /**
     * Uses only the shared runner and its owner-thread meter.
     */
    object FirstAdapter : JvmOperationReportAdapter {
        override val rows: List<JvmOperationReportRow> = listOf(JvmOperationReportRow("first", "example.First.operation", emptyMap(), "Complete operation"))

        override fun collect(plan: PerformancePlan): Map<String, JsonObject> = JvmPerformanceRunner.sequence(plan.samples, mapOf("first" to plan.samples)) { _, sequence -> sequence.sample("first") {} }
    }

    /**
     * Provides an independent adapter identity for discovery controls.
     */
    object SecondAdapter : JvmOperationReportAdapter {
        override val rows: List<JvmOperationReportRow> = listOf(JvmOperationReportRow("second", "example.Second.operation", emptyMap(), "Complete operation"))

        override fun collect(plan: PerformancePlan): Map<String, JsonObject> = JvmPerformanceRunner.sequence(plan.samples, mapOf("second" to plan.samples)) { _, sequence -> sequence.sample("second") {} }
    }
}
