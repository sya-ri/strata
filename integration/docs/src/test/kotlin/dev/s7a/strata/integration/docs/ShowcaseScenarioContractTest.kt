@file:OptIn(InternalStrataRuntimeApi::class)

package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.integration.minecraft.fabric.createConfirmScreenDefinition
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.projection.BuiltinProjection
import dev.s7a.strata.projection.ProjectionType
import dev.s7a.strata.projection.ProjectionValue
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.resource.ResourceId
import dev.s7a.strata.runtime.remote.RemoteBuiltins
import dev.s7a.strata.runtime.remote.RemoteComponentRuntime
import dev.s7a.strata.runtime.remote.RemoteLimits
import dev.s7a.strata.runtime.remote.RemoteMessage
import dev.s7a.strata.runtime.remote.RemoteRegistry
import dev.s7a.strata.runtime.remote.RemoteServerSession
import dev.s7a.strata.runtime.remote.RemoteTree
import dev.s7a.strata.spi.InternalStrataRuntimeApi
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import dev.s7a.strata.ui.UiDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Verifies typed catalog metadata and exact compiled headless showcase geometry.
 */
internal class ShowcaseScenarioContractTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    @Suppress("LongMethod")
    fun catalogHasMinecraftComponentOrderSourcesAndViewports() {
        ShowcaseScenarioCatalog.validate()
        val scenarios = ShowcaseScenarioCatalog.components
        assertEquals(
            DocumentedComponent.entries,
            scenarios.map { scenario -> scenario.component },
        )
        assertEquals(
            listOf(
                "MinecraftRowExample.kt" to "row",
                "MinecraftFlowRowShowcaseExample.kt" to "flow-row",
                "MinecraftColumnExample.kt" to "column",
                "MinecraftStackExample.kt" to "stack",
                "MinecraftGridExample.kt" to "grid",
                "MinecraftSpacerExample.kt" to "spacer",
                "MinecraftObserveExample.kt" to "observe",
                "MinecraftTextExample.kt" to "text",
                "MinecraftTextFieldShowcaseExample.kt" to "text-field",
                "MinecraftTextAreaShowcaseExample.kt" to "text-area",
                "MinecraftButtonExample.kt" to "button",
                "MinecraftCheckboxExample.kt" to "checkbox",
                "MinecraftCycleButtonExample.kt" to "cycle-button",
                "MinecraftSliderExample.kt" to "slider",
                "MinecraftTabExample.kt" to "tab",
                "MinecraftScrollAreaExample.kt" to "scroll-area",
                "MinecraftScrollbarExample.kt" to "scrollbar",
                "MinecraftVirtualListExample.kt" to "virtual-list",
                "MinecraftSelectionListExample.kt" to "selection-list",
                "MinecraftImageExample.kt" to "image",
                "MinecraftCanvasExample.kt" to "canvas",
                "MinecraftTiledImageExample.kt" to "tiled-image",
                "MinecraftSlotShowcaseExample.kt" to "slot",
                "MinecraftPlayerHeadExample.kt" to "player-head",
                "MinecraftLoadingIndicatorExample.kt" to "loading-indicator",
                "MinecraftProgressBarExample.kt" to "progress-bar",
            ),
            scenarios.map { scenario -> scenario.source.relativePath.substringAfterLast('/') to scenario.source.slug },
        )
        assertEquals(
            listOf(
                IntSize(136, 64),
                IntSize(168, 60),
                IntSize(120, 64),
                IntSize(64, 64),
                IntSize(64, 64),
                IntSize(160, 64),
                IntSize(160, 48),
                IntSize(192, 88),
                IntSize(216, 64),
                IntSize(226, 80),
                IntSize(166, 64),
                IntSize(166, 36),
                IntSize(166, 36),
                IntSize(166, 36),
                IntSize(160, 64),
                IntSize(120, 48),
                IntSize(94, 48),
                IntSize(120, 48),
                IntSize(120, 48),
                IntSize(64, 64),
                IntSize(96, 64),
                IntSize(112, 88),
                IntSize(64, 64),
                IntSize(64, 64),
                IntSize(32, 24),
                IntSize(116, 28),
            ),
            scenarios.map { scenario -> scenario.viewport },
        )
        val denseTextComponents = setOf(DocumentedComponent.Text, DocumentedComponent.TextField, DocumentedComponent.TextArea)
        assertEquals(
            DocumentedComponent.entries.map { component -> if (component in denseTextComponents) 2 else 1 },
            scenarios.map { scenario -> scenario.scale },
        )
    }

    @Test
    fun documentedComponentTreesMatchTheirCompiledDefinitions() {
        val assets = ShowcaseMinecraftAssetFixture(temporary).assets()
        ShowcaseScenarioCatalog.components.forEach { scenario ->
            val actual = compiledTopology(ShowcaseHeadlessRenderer.componentDefinition(scenario, assets), scenario.component)
            assertEquals(documentedTopology(scenario.tree), actual, scenario.component.name)
        }
    }

    @Test
    fun everyComponentTreeRecursivelyContainsItsFeaturedComponent() {
        ShowcaseScenarioCatalog.components.forEach { scenario ->
            assertTrue(contains(scenario.tree, scenario.component), scenario.component.apiMethodName)
        }
    }

    @Test
    fun componentSourcesAreDedicatedUniqueAndSeparateFromCompleteScreens() {
        val componentSources = ShowcaseScenarioCatalog.components.map { scenario -> scenario.source.relativePath }
        val completeScreenSources =
            (listOf(ShowcaseScenarioCatalog.overview.source) + ShowcaseScenarioCatalog.screens.map { scenario -> scenario.source })
                .map { source -> source.relativePath }
                .toSet()
        assertEquals(componentSources.size, componentSources.toSet().size)
        assertTrue(componentSources.none { source -> source in completeScreenSources })
    }

    @Test
    fun overviewHasExactRootAndContainerDetails() {
        val overview = ShowcaseScenarioCatalog.overview
        assertEquals(
            "integration/shared/minecraft-fabric/scenarios/gui-extractor/src/screen-examples/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt",
            overview.source.relativePath,
        )
        assertEquals("overview", overview.source.slug)
        assertEquals(IntSize(320, 180), overview.viewport)
        assertEquals(1, overview.scale)
        assertEquals(listOf(DocumentedComponent.Stack), overview.trees.map { tree -> tree.component })
    }

    @Test
    fun overviewTopologyMatchesTheCompiledCompleteScreen() {
        val expected = ShowcaseScenarioCatalog.overview.trees.flatMap { documentedTopology(it) }
        assertEquals(expected, compiledTopology(createConfirmScreenDefinition()))
    }

    @Test
    fun everyScenarioUsesCompiledSourcesAndCompleteScreensKeepScaleOne() {
        ShowcaseScenarioCatalog.components.forEach { scenario ->
            assertTrue(scenario.source.relativePath.startsWith("integration/shared/minecraft-fabric/scenarios/gui-extractor/src/gametest/kotlin/"))
        }
        ShowcaseScenarioCatalog.screens.forEach { scenario ->
            assertEquals(1, scenario.scale)
            val sourceRoot =
                when (scenario.screen) {
                    DocumentedScreen.SocialInteractions, DocumentedScreen.IndustrialController, DocumentedScreen.PowerMilestones -> "scenarios/gui-extractor/src/screen-examples/kotlin"
                    DocumentedScreen.SynchronizedInventory -> "screen/gui-extractor/src/gametest/kotlin"
                }
            assertTrue(scenario.source.relativePath.startsWith("integration/shared/minecraft-fabric/$sourceRoot/"))
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

    private fun documentedTopology(
        tree: ShowcaseTree,
        depth: Int = 0,
    ): List<Pair<Int, DocumentedComponent>> = listOf(depth to tree.component) + tree.children.flatMap { documentedTopology(it, depth + 1) }

    private fun compiledTopology(
        definition: UiDefinition,
        featured: DocumentedComponent? = null,
    ): List<Pair<Int, DocumentedComponent>> {
        val registry = RemoteRegistry().also(RemoteBuiltins::register)
        val runtime = RemoteComponentRuntime()
        val content = definition.transfer().content
        val messages = mutableListOf<RemoteMessage>()
        val limits = RemoteLimits(reconstructionMillis = 60_000)
        return RemoteServerSession(1, ProjectionValue.Absent, registry.types, limits, messages::add) { runtime.evaluate(content) }.use { session ->
            session.tick()
            val tree = (messages.single() as RemoteMessage.Snapshot).tree
            projectedTopology(tree, tree.root, 0, featured)
        }
    }

    private fun projectedTopology(
        tree: RemoteTree,
        identity: Long,
        depth: Int,
        featured: DocumentedComponent?,
    ): List<Pair<Int, DocumentedComponent>> {
        val node = tree.nodes.getValue(identity)
        val type = node.declaration.type
        if (type == BuiltinProjection.TiledImageLayer.type) return emptyList()
        if (type == BuiltinProjection.StateComponent.type || type == BuiltinProjection.TiledImagePosition.type) {
            return node.children.flatMap { projectedTopology(tree, it, depth, featured) }
        }
        // SelectionList composes VirtualList; materialized rows and the private tile layer are omitted from authored trees.
        val component =
            if (type == BuiltinProjection.VirtualList.type && featured == DocumentedComponent.SelectionList && depth == 0) {
                DocumentedComponent.SelectionList
            } else {
                projectedComponents.getValue(type)
            }
        val children = if (type == BuiltinProjection.VirtualList.type) emptyList() else node.children.flatMap { projectedTopology(tree, it, depth + 1, featured) }
        return listOf(depth to component) + children
    }

    private fun profileType(
        path: String,
        version: Int = 1,
    ): ProjectionType = ProjectionType(ResourceId("strata", "profile/$path"), version)

    private val projectedComponents =
        mapOf(
            BuiltinProjection.Row.type to DocumentedComponent.Row,
            BuiltinProjection.Column.type to DocumentedComponent.Column,
            BuiltinProjection.Stack.type to DocumentedComponent.Stack,
            BuiltinProjection.Grid.type to DocumentedComponent.Grid,
            BuiltinProjection.FlowRow.type to DocumentedComponent.FlowRow,
            BuiltinProjection.Spacer.type to DocumentedComponent.Spacer,
            BuiltinProjection.Observe.type to DocumentedComponent.Observe,
            BuiltinProjection.VirtualList.type to DocumentedComponent.VirtualList,
            BuiltinProjection.CanvasPixels.type to DocumentedComponent.Canvas,
            BuiltinProjection.TiledImage.type to DocumentedComponent.TiledImage,
            profileType("text", 2) to DocumentedComponent.Text,
            profileType("button") to DocumentedComponent.Button,
            profileType("text_field", 2) to DocumentedComponent.TextField,
            profileType("text_area", 2) to DocumentedComponent.TextArea,
            profileType("checkbox") to DocumentedComponent.Checkbox,
            profileType("cycle_button") to DocumentedComponent.CycleButton,
            profileType("slider") to DocumentedComponent.Slider,
            profileType("tab") to DocumentedComponent.Tab,
            profileType("progress_bar") to DocumentedComponent.ProgressBar,
            profileType("loading_indicator") to DocumentedComponent.LoadingIndicator,
            profileType("image") to DocumentedComponent.Image,
            profileType("player_head") to DocumentedComponent.PlayerHead,
            profileType("slot") to DocumentedComponent.Slot,
            profileType("scroll_area") to DocumentedComponent.ScrollArea,
            profileType("scrollbar") to DocumentedComponent.Scrollbar,
        )

    private fun contains(
        tree: ShowcaseTree,
        component: DocumentedComponent,
    ): Boolean = tree.component == component || tree.children.any { child -> contains(child, component) }
}
