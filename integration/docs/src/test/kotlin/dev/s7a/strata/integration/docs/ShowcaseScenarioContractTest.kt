package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies typed catalog metadata and exact compiled headless showcase geometry.
 */
internal class ShowcaseScenarioContractTest {
    @Test
    fun catalogHasMinecraftComponentOrderSourcesAndViewports() {
        ShowcaseScenarioCatalog.validate()
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            DocumentedComponent.entries,
            scenarios.map { scenario -> scenario.component },
        )
        assertEquals(
            listOf(
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftTextExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftTextFieldExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftButtonExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftScrollExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSlotExample.kt",
            ),
            scenarios.map { scenario -> scenario.source.relativePath },
        )
        assertEquals(listOf("text", "text-field", "button", "scroll", "slot"), scenarios.map { scenario -> scenario.source.slug })
        assertEquals(
            listOf(IntSize(150, 20), IntSize(200, 20), IntSize(150, 20), IntSize(320, 94), IntSize(24, 24)),
            scenarios.map { scenario -> scenario.viewport },
        )
        assertEquals(List(5) { 1 }, scenarios.map { scenario -> scenario.scale })
    }

    @Test
    fun componentTreesHaveExactTypedDetailsAndNoInventedChildren() {
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(listOf(ShowcaseTreeDetail.Size(150, 20)), scenarios[0].tree.details)
        assertEquals(listOf(ShowcaseTreeDetail.Size(200, 20)), scenarios[1].tree.details)
        assertTrue(scenarios[2].tree.details.isEmpty())
        assertEquals(listOf(ShowcaseTreeDetail.Size(320, 94), ShowcaseTreeDetail.ScrollRate(9)), scenarios[3].tree.details)
        assertEquals(List(12) { DocumentedComponent.Text }, scenarios[3].tree.children.map { child -> child.component })
        assertTrue(scenarios[3].tree.children.all { child -> child.details.isEmpty() && child.children.isEmpty() })
        assertEquals(listOf(ShowcaseTreeDetail.SlotHighlightable(true), ShowcaseTreeDetail.Size(18, 18)), scenarios[4].tree.details)
        listOf(scenarios[0], scenarios[1], scenarios[2], scenarios[4]).forEach { scenario ->
            assertEquals(scenario.component, scenario.tree.component)
            assertTrue(scenario.tree.children.isEmpty())
        }
        assertEquals(scenarios[3].component, scenarios[3].tree.component)
    }

    @Test
    fun overviewHasExactRootAndContainerDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertEquals(
            "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt",
            overview.source.relativePath,
        )
        assertEquals("overview", overview.source.slug)
        assertEquals(IntSize(320, 180), overview.viewport)
        assertEquals(1, overview.scale)
        assertEquals(
            listOf(DocumentedComponent.Text, DocumentedComponent.Text, DocumentedComponent.Button, DocumentedComponent.Button),
            overview.trees.map { tree -> tree.component },
        )
    }

    @Test
    fun overviewHasExactLeafTopologyAndDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertTrue(overview.trees.all { tree -> tree.details.isEmpty() && tree.children.isEmpty() })
    }

    @Test
    fun everyScenarioUsesLoadedGameSourcesAndScaleOne() {
        (listOf(ShowcaseScenarioCatalog.overview) + ShowcaseScenarioCatalog.components).forEach { scenario ->
            assertEquals(1, scenario.scale)
            assertTrue(scenario.source.relativePath.startsWith("integration/minecraft-fabric-26.2/src/gametest/kotlin/"))
        }
    }
}
