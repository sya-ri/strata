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
                "MinecraftOverviewExample.kt" to "overview",
                "MinecraftOverviewExample.kt" to "overview",
                "MinecraftOverviewExample.kt" to "overview",
                "MinecraftSlotExample.kt" to "slot",
                "MinecraftProgressExample.kt" to "progress-screen",
                "MinecraftTextExample.kt" to "text",
                "MinecraftTextFieldExample.kt" to "text-field",
                "MinecraftButtonExample.kt" to "button",
                "MinecraftSocialExample.kt" to "social-screen",
                "MinecraftScrollExample.kt" to "scroll",
                "MinecraftIndustrialExample.kt" to "industrial-screen",
                "MinecraftSlotExample.kt" to "slot",
                "MinecraftPlayerHeadExample.kt" to "player-head",
            ),
            scenarios.map { scenario -> scenario.source.relativePath.substringAfterLast('/') to scenario.source.slug },
        )
        assertEquals(
            listOf(
                IntSize(320, 180),
                IntSize(320, 180),
                IntSize(320, 180),
                IntSize(320, 240),
                IntSize(320, 180),
                IntSize(150, 20),
                IntSize(200, 20),
                IntSize(150, 20),
                IntSize(320, 240),
                IntSize(320, 94),
                IntSize(32, 32),
                IntSize(24, 24),
                IntSize(24, 24),
            ),
            scenarios.map { scenario -> scenario.viewport },
        )
        assertEquals(List(DocumentedComponent.entries.size) { 1 }, scenarios.map { scenario -> scenario.scale })
    }

    @Test
    fun componentTreesHaveExactTypedDetailsAndNoInventedChildren() {
        val scenarios = ShowcaseScenarioCatalog.components.associateBy { scenario -> scenario.component }
        assertEquals(listOf(ShowcaseTreeDetail.Size(150, 20)), scenarios.getValue(DocumentedComponent.Text).tree.details)
        assertEquals(listOf(ShowcaseTreeDetail.Size(200, 20)), scenarios.getValue(DocumentedComponent.TextField).tree.details)
        assertTrue(
            scenarios
                .getValue(DocumentedComponent.Button)
                .tree.details
                .isEmpty(),
        )
        val scroll = scenarios.getValue(DocumentedComponent.Scroll).tree
        assertEquals(listOf(ShowcaseTreeDetail.Size(320, 94), ShowcaseTreeDetail.ScrollRate(9)), scroll.details)
        assertEquals(List(12) { DocumentedComponent.Text }, scroll.children.map { child -> child.component })
        assertTrue(scroll.children.all { child -> child.details.isEmpty() && child.children.isEmpty() })
        val grid = scenarios.getValue(DocumentedComponent.Grid).tree
        assertEquals(listOf(ShowcaseTreeDetail.GridColumns(9)), grid.details)
        assertEquals(List(27) { DocumentedComponent.Slot }, grid.children.map { child -> child.component })
        assertEquals(
            listOf(ShowcaseTreeDetail.SlotHighlightable(true), ShowcaseTreeDetail.Size(18, 18)),
            scenarios.getValue(DocumentedComponent.Slot).tree.details,
        )
        scenarios.values.forEach { scenario -> assertEquals(scenario.component, scenario.tree.component) }
    }

    @Test
    fun overviewHasExactRootAndContainerDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertEquals(
            "integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt",
            overview.source.relativePath,
        )
        assertEquals("overview", overview.source.slug)
        assertEquals(IntSize(320, 180), overview.viewport)
        assertEquals(1, overview.scale)
        assertEquals(listOf(DocumentedComponent.Stack), overview.trees.map { tree -> tree.component })
    }

    @Test
    fun overviewHasExactLeafTopologyAndDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        val stack = overview.trees.single()
        assertEquals(listOf(ShowcaseTreeDetail.Size(320, 180)), stack.details)
        assertEquals(listOf(DocumentedComponent.Column), stack.children.map { child -> child.component })
        assertEquals(
            listOf(DocumentedComponent.Column, DocumentedComponent.Row),
            stack.children
                .single()
                .children
                .map { child -> child.component },
        )
    }

    @Test
    fun everyScenarioUsesLoadedGameSourcesAndScaleOne() {
        (listOf(ShowcaseScenarioCatalog.overview) + ShowcaseScenarioCatalog.components).forEach { scenario ->
            assertEquals(1, scenario.scale)
            assertTrue(scenario.source.relativePath.startsWith("integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/"))
        }
        ShowcaseScenarioCatalog.screens.forEach { scenario ->
            assertEquals(1, scenario.scale)
            assertTrue(scenario.source.relativePath.startsWith("integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/"))
        }
    }

    @Test
    fun completeScreensHaveExactTypedOrderSourcesAndViewports() {
        val screens = ShowcaseScenarioCatalog.screens
        assertEquals(DocumentedScreen.entries, screens.map { scenario -> scenario.screen })
        assertEquals(
            listOf(
                "MinecraftSocialExample.kt" to "social-screen",
                "MinecraftInventoryExample.kt" to "inventory-screen",
                "MinecraftIndustrialExample.kt" to "industrial-screen",
                "MinecraftProgressExample.kt" to "progress-screen",
            ),
            screens.map { scenario -> scenario.source.relativePath.substringAfterLast('/') to scenario.source.slug },
        )
        assertEquals(listOf(320, 320, 320, 320), screens.map { scenario -> scenario.viewportWidth })
        assertEquals(listOf(240, 240, 180, 180), screens.map { scenario -> scenario.viewportHeight })
    }
}
