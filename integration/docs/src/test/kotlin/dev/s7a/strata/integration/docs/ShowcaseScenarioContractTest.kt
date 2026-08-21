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
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftMenuBackgroundExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftContainerBackgroundExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSlotExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftTextExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftTextFieldExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftButtonExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftScrollExample.kt",
            ),
            scenarios.map { scenario -> scenario.source.relativePath },
        )
        assertEquals(listOf("menu-background", "container-background", "slot", "text", "text-field", "button", "scroll"), scenarios.map { scenario -> scenario.source.slug })
        assertEquals(
            listOf(IntSize(32, 32), IntSize(176, 168), IntSize(24, 24), IntSize(150, 20), IntSize(200, 20), IntSize(150, 20), IntSize(320, 94)),
            scenarios.map { scenario -> scenario.viewport },
        )
        assertEquals(List(7) { 1 }, scenarios.map { scenario -> scenario.scale })
    }

    @Test
    fun componentTreesHaveExactTypedDetailsAndNoInventedChildren() {
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(listOf(ShowcaseTreeDetail.FillMaxSize), scenarios[0].tree.details)
        assertEquals(listOf(ShowcaseTreeDetail.ContainerRows(3), ShowcaseTreeDetail.Size(176, 168)), scenarios[1].tree.details)
        assertEquals(listOf(ShowcaseTreeDetail.SlotHighlightable(true), ShowcaseTreeDetail.Size(18, 18)), scenarios[2].tree.details)
        assertEquals(listOf(ShowcaseTreeDetail.Size(150, 20)), scenarios[3].tree.details)
        assertEquals(listOf(ShowcaseTreeDetail.Size(200, 20)), scenarios[4].tree.details)
        assertTrue(scenarios[5].tree.details.isEmpty())
        assertEquals(listOf(ShowcaseTreeDetail.Size(320, 94), ShowcaseTreeDetail.ScrollRate(9)), scenarios[6].tree.details)
        assertEquals(List(12) { DocumentedComponent.Text }, scenarios[6].tree.children.map { child -> child.component })
        assertTrue(scenarios[6].tree.children.all { child -> child.details.isEmpty() && child.children.isEmpty() })
        scenarios.take(6).forEach { scenario ->
            assertEquals(scenario.component, scenario.tree.component)
            assertTrue(scenario.tree.children.isEmpty())
        }
        assertEquals(scenarios[6].component, scenarios[6].tree.component)
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
        assertEquals(DocumentedComponent.MenuBackground, overview.tree.component)
        assertEquals(listOf(ShowcaseTreeDetail.FillMaxSize), overview.tree.details)
        assertEquals(
            listOf(DocumentedComponent.Text, DocumentedComponent.Text, DocumentedComponent.Button, DocumentedComponent.Button),
            overview.tree.children.map { child -> child.component },
        )
    }

    @Test
    fun overviewHasExactLeafTopologyAndDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertTrue(overview.tree.children.all { child -> child.children.isEmpty() })
    }

    @Test
    fun everyScenarioUsesLoadedGameSourcesAndScaleOne() {
        (listOf(ShowcaseScenarioCatalog.overview) + ShowcaseScenarioCatalog.components).forEach { scenario ->
            assertEquals(1, scenario.scale)
            assertTrue(scenario.source.relativePath.startsWith("integration/minecraft-fabric-26.2/src/gametest/kotlin/"))
        }
    }
}
