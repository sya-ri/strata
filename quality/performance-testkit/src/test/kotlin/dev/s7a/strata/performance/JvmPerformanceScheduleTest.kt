package dev.s7a.strata.performance

import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checks callback-driven collection without owning a scheduler or introducing another clock.
 */
class JvmPerformanceScheduleTest {
    @Test
    fun advancesOneOperationAndPublishesOnlyCompleteDetachedEvidence() {
        val order = mutableListOf<String>()
        JvmPerformanceSchedule(
            "ticks",
            PerformancePlan(warmup = 2, samples = 2),
            beforeSample = { order.add("prepare:$it") },
            afterOperation = { index, result -> order.add("assert:$index:$result") },
        ) { index ->
            order.add("operation:$index")
            index
        }.use { schedule ->
            assertFailsWith<IllegalStateException> { schedule.complete() }
            assertFalse(schedule.advance())
            assertFalse(schedule.advance())
            assertFalse(schedule.advance())
            assertTrue(schedule.advance())
            assertTrue(schedule.advance())
            assertEquals(listOf("operation:0", "operation:1", "prepare:0", "operation:0", "assert:0:0", "prepare:1", "operation:1", "assert:1:1"), order)
            val result = schedule.complete()
            assertEquals(1, result.value)
            assertEquals(2, result.evidence.get("samples").asInt)
            result.evidence.addProperty("samples", 99)
            assertEquals(
                2,
                schedule
                    .complete()
                    .evidence
                    .get("samples")
                    .asInt,
            )
        }
    }

    @Test
    fun rejectsCallbackFailuresAndNeverAllowsResumeOrEvidence() {
        val callbacks = listOf("warmup", "prepare", "operation", "assert")
        callbacks.forEach { failureAt ->
            val schedule =
                JvmPerformanceSchedule(
                    failureAt,
                    PerformancePlan(warmup = if (failureAt.contentEquals("warmup")) 1 else 0, samples = 1),
                    beforeSample = { if (failureAt.contentEquals("prepare")) error("prepare") },
                    afterOperation = { _, _: Int -> if (failureAt.contentEquals("assert")) error("assert") },
                ) {
                    if (failureAt in setOf("warmup", "operation")) error("operation")
                    1
                }
            assertFailsWith<IllegalStateException> { schedule.advance() }
            assertFailsWith<IllegalStateException> { schedule.advance() }
            assertFailsWith<IllegalStateException> { schedule.complete() }
            schedule.close()
            schedule.close()
        }
    }

    @Test
    fun preservesPhysicalOwnerAndReleasesApplicationReferencesOnClose() {
        val operation = { _: Int -> Any() }
        val fixture = Any()
        val schedule = JvmPerformanceSchedule("owner", PerformancePlan(warmup = 0, samples = 1), fixture, operation = operation)
        // No diagnostic capture is attempted: this fixture tests terminal ownership rather than host reflection.
        CompletableFuture
            .runAsync {
                assertFailsWith<IllegalStateException> { schedule.advance() }
                assertFailsWith<IllegalStateException> { schedule.close() }
            }.join()
        schedule.close()
        listOf("diagnosticsOwner", "operation", "beforeSample", "afterOperation", "last", "meter", "monitor").forEach { name ->
            val field = schedule.javaClass.getDeclaredField(name).apply { isAccessible = true }
            assertEquals(null, field.get(schedule), "Retained closed field: $name")
        }
        assertFailsWith<IllegalStateException> { schedule.complete() }
    }
}
