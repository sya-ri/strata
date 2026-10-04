package dev.s7a.strata.gradle.performance

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Checks future publication additions and malformed registrations without launching measurement.
 */
internal class PublishedPerformanceInventoryTest {
    private val row = ":api\tJvm\tquality/benchmarks/Fixture.kt\t:quality:benchmarks:verifyWork"

    @Test
    fun `one module may register multiple physical hosts`() {
        val web = ":api\tWeb\tintegration/web/Fixture.kt\t:integration:web:verifyWeb"
        assertEquals(2, verify(setOf(":api"), listOf("# Reviewed registrations", row, web)))
    }

    @Test
    fun `new and removed publications cannot silently inherit coverage`() {
        assertThrows(IllegalArgumentException::class.java) { verify(setOf(":api", ":runtime:new"), listOf(row)) }
        assertThrows(IllegalArgumentException::class.java) { verify(setOf(":runtime:new"), listOf(row)) }
    }

    @Test
    fun `duplicates unknown hosts unsafe paths and missing entry points fail`() {
        val invalid = listOf(row, row.replace("Jvm", "Unknown"), row.replace("quality/benchmarks/Fixture.kt", "../Fixture.kt"), row.replace("quality/benchmarks/Fixture.kt", "/Fixture.kt"), row.replace(":quality:benchmarks:verifyWork", "verifyWork"), row.replace("Fixture.kt", "Missing.kt"), row.replace("verifyWork", "missing"))
        invalid.forEach { changed ->
            val rows = if (changed == row) listOf(row, row) else listOf(changed)
            assertThrows(IllegalArgumentException::class.java) { verify(setOf(":api"), rows) }
        }
    }

    private fun verify(
        projects: Set<String>,
        rows: List<String>,
    ): Int = PublishedPerformanceInventory.verify(projects, rows, { it.endsWith("Fixture.kt") }, { it.endsWith("verifyWork") || it.endsWith("verifyWeb") })
}
