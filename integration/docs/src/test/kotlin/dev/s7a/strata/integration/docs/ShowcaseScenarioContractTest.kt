package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.HorizontalAlignment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies typed catalog metadata and exact compiled headless showcase geometry.
 */
internal class ShowcaseScenarioContractTest {
    @Test
    fun catalogHasLockedOrderReferencesAndViewports() {
        ShowcaseScenarioCatalog.validate()
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            listOf(DocumentedComponent.Row, DocumentedComponent.Column, DocumentedComponent.Box, DocumentedComponent.Spacer),
            scenarios.map { scenario -> scenario.component },
        )
        assertEquals(
            listOf(
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftRowExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftColumnExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftBoxExample.kt",
                "integration/minecraft-fabric-26.2/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSpacerExample.kt",
            ),
            scenarios.map { scenario -> scenario.source.relativePath },
        )
        assertEquals(listOf("row", "column", "box", "spacer"), scenarios.map { scenario -> scenario.source.slug })
        assertEquals(List(4) { IntSize(320, 180) }, scenarios.map { scenario -> scenario.viewport })
        assertEquals(List(4) { 1 }, scenarios.map { scenario -> scenario.scale })
    }

    @Test
    fun linearScenariosHaveExactTypedDetailsAndTopology() {
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            listOf(
                ShowcaseTreeDetail.Size(320, 180),
                ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
            ),
            scenarios[0].tree.details,
        )
        assertEquals(
            listOf(
                ShowcaseTreeDetail.Size(320, 180),
                ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
            ),
            scenarios[1].tree.details,
        )
        assertEquals(
            listOf(DocumentedComponent.Spacer, DocumentedComponent.Spacer, DocumentedComponent.Row),
            scenarios[0].tree.children.map { child -> child.component },
        )
        assertEquals(
            listOf(
                listOf(ShowcaseTreeDetail.Height(20)),
                listOf(ShowcaseTreeDetail.Height(11)),
                listOf(ShowcaseTreeDetail.Spacing(10)),
            ),
            scenarios[0].tree.children.map { child -> child.details },
        )
        assertEquals(listOf(DocumentedComponent.Spacer, DocumentedComponent.Spacer, DocumentedComponent.Spacer), scenarios[1].tree.children.map { child -> child.component })
        assertEquals(
            listOf(
                listOf(ShowcaseTreeDetail.Height(20)),
                listOf(ShowcaseTreeDetail.Height(11)),
                listOf(ShowcaseTreeDetail.Height(4)),
            ),
            scenarios[1].tree.children.map { child -> child.details },
        )
    }

    @Test
    fun boxAndSpacerScenariosHaveExactTypedDetailsAndTopology() {
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            listOf(
                ShowcaseTreeDetail.Size(320, 180),
                ShowcaseTreeDetail.BoxContentAlignment(Alignment.Center),
            ),
            scenarios[2].tree.details,
        )
        assertTrue(scenarios[2].tree.children.isEmpty())
        assertEquals(DocumentedComponent.Column, scenarios[3].tree.component)
        assertEquals(
            listOf(
                ShowcaseTreeDetail.Size(320, 180),
                ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
            ),
            scenarios[3].tree.details,
        )
        assertEquals(
            listOf(listOf(ShowcaseTreeDetail.Height(20)), listOf(ShowcaseTreeDetail.Height(51))),
            scenarios[3].tree.children.map { child -> child.details },
        )
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
        assertEquals(DocumentedComponent.Column, overview.tree.component)
        assertEquals(
            listOf(
                ShowcaseTreeDetail.Size(320, 180),
                ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
            ),
            overview.tree.details,
        )
        assertEquals(2, overview.tree.children.size)
        assertEquals(listOf(DocumentedComponent.Spacer, DocumentedComponent.Spacer), overview.tree.children.map { child -> child.component })
        assertEquals(
            listOf(listOf(ShowcaseTreeDetail.Height(20)), listOf(ShowcaseTreeDetail.Height(11))),
            overview.tree.children.map { child -> child.details },
        )
    }

    @Test
    fun overviewHasExactLeafTopologyAndDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertTrue(overview.tree.children.all { child -> child.children.isEmpty() })
    }

    @Test
    fun everyScenarioUsesTheExactLoadedGameCropContract() {
        val scenarios = listOf(ShowcaseScenarioCatalog.overview) + ShowcaseScenarioCatalog.components
        scenarios.forEach { scenario ->
            assertEquals(IntSize(320, 180), scenario.viewport)
            assertEquals(1, scenario.scale)
            assertTrue(scenario.source.relativePath.startsWith("integration/minecraft-fabric-26.2/src/gametest/kotlin/"))
        }
    }
}
