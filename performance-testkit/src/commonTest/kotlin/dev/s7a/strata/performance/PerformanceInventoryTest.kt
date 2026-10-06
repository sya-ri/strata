package dev.s7a.strata.performance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Public-surface changes and unknown source impact cannot bypass executable coverage registration.
 */
class PerformanceInventoryTest {
    @Test
    fun additionsRemovalsAndUnknownAssignmentsFailBeforeSelection() {
        val assignments = mapOf("api" to mapOf("Text" to "text"))
        assertFailsWith<IllegalArgumentException> { inventory(mapOf("api" to setOf("Text", "New")), assignments).verify(setOf("text")) }
        assertFailsWith<IllegalArgumentException> { inventory(mapOf("api" to setOf("Renamed")), assignments).verify(setOf("text")) }
        assertFailsWith<IllegalArgumentException> { inventory(mapOf("api" to setOf("Text"), "new-module" to setOf("New")), assignments).verify(setOf("text")) }
        assertFailsWith<IllegalArgumentException> { inventory(mapOf("api" to setOf("Text")), assignments).verify(setOf("other")) }
    }

    @Test
    fun selectionUnionsOverlappingOwnersAndFallsBackForUnknownDeletedPaths() {
        val cases = listOf(scenario("text"), scenario("layout"))
        val coverage = PerformanceCoverage(cases.associate { it.id to setOf(PerformanceHost.Jvm) }, cases)
        val surface =
            PerformanceInventory(
                mapOf("api" to setOf("Text", "Column")),
                mapOf("api" to mapOf("Text" to "text", "Column" to "layout")),
                mapOf("api/" to setOf("layout"), "api/text/" to setOf("text")),
            )
        assertEquals(setOf("text", "layout"), coverage.selectChangedPaths(surface, setOf("api/text/Deleted.kt")).map { it.id }.toSet())
        assertEquals(listOf("layout"), coverage.selectChangedPaths(surface, setOf("api/layout/Column.kt")).map { it.id })
        assertEquals(2, coverage.selectChangedPaths(surface, setOf("new-module/New.kt")).size)
        assertEquals(2, coverage.selectChangedPaths(surface).size)
        assertEquals(emptyList(), coverage.selectChangedPaths(surface, emptySet()))
        assertFailsWith<IllegalArgumentException> { coverage.selectChangedPaths(surface, setOf("../outside.kt")) }
        assertFailsWith<IllegalArgumentException> { coverage.selectChangedPaths(surface, setOf("api\\text\\Text.kt")) }
    }

    @Test
    fun constructorDetachesSurfaceAndOwnershipFromMutableInputs() {
        val symbols = mutableSetOf("Text")
        val assigned = mutableMapOf("Text" to "text")
        val owners = mutableSetOf("text")
        val surface = PerformanceInventory(mapOf("api" to symbols), mapOf("api" to assigned), mapOf("api/" to owners))
        symbols.clear()
        assigned.clear()
        owners.clear()
        surface.verify(setOf("text"))
        assertEquals(setOf("text"), surface.affectedFeatures(setOf("text"), setOf("api/Text.kt")))
    }

    private fun inventory(
        surfaces: Map<String, Set<String>>,
        assignments: Map<String, Map<String, String>>,
    ): PerformanceInventory = PerformanceInventory(surfaces, assignments, mapOf("api/" to setOf("text")))

    private fun scenario(feature: String): PerformanceScenario = PerformanceScenario(feature, setOf(feature), setOf(PerformanceHost.Jvm), setOf(PerformancePhase.Idle), "fixed-v1")
}
