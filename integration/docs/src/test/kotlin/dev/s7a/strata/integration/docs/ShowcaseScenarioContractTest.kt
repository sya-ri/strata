package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies typed catalog metadata and exact compiled headless showcase geometry.
 */
internal class ShowcaseScenarioContractTest {
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
    @Suppress("LongMethod")
    fun componentTreesHaveExactTypedDetailsAndNoInventedChildren() {
        val black = ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt()))
        val expected =
            listOf(
                expectedTree(
                    DocumentedComponent.Row,
                    listOf(
                        ShowcaseTreeDetail.Size(136, 64),
                        black,
                        ShowcaseTreeDetail.Spacing(4),
                        ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                        ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                    ),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                ),
                expectedTree(
                    DocumentedComponent.FlowRow,
                    listOf(
                        ShowcaseTreeDetail.Size(168, 60),
                        black,
                        ShowcaseTreeDetail.Padding(8),
                        ShowcaseTreeDetail.FlowRowSpacing(horizontal = 4, vertical = 4),
                        ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                        ShowcaseTreeDetail.FlowRowDefaultAlignment(VerticalAlignment.Center),
                    ),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(72, 20))),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(56, 20))),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(92, 20))),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(52, 20))),
                ),
                expectedTree(
                    DocumentedComponent.Column,
                    listOf(
                        ShowcaseTreeDetail.Size(120, 64),
                        black,
                        ShowcaseTreeDetail.Spacing(4),
                        ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                        ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                    ),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(96, 20))),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(96, 20))),
                ),
                expectedTree(
                    DocumentedComponent.Stack,
                    listOf(
                        ShowcaseTreeDetail.Size(64, 64),
                        black,
                        ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
                    ),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(56, 20))),
                    expectedTree(
                        DocumentedComponent.Spacer,
                        listOf(
                            ShowcaseTreeDetail.Size(10, 10),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFFE53935.toInt())),
                            ShowcaseTreeDetail.StackAlign(Alignment.CenterEnd),
                        ),
                    ),
                ),
                expectedTree(
                    DocumentedComponent.Grid,
                    listOf(
                        ShowcaseTreeDetail.Size(64, 64),
                        black,
                        ShowcaseTreeDetail.GridColumns(3),
                        ShowcaseTreeDetail.GridSpacing(horizontal = 2, vertical = 2),
                    ),
                    *Array(9) {
                        expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(20, 20)))
                    },
                ),
                expectedTree(
                    DocumentedComponent.Row,
                    listOf(
                        ShowcaseTreeDetail.Size(160, 64),
                        black,
                        ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                        ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                    ),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                    expectedTree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(16, 20))),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                ),
                expectedTree(
                    DocumentedComponent.Stack,
                    listOf(
                        ShowcaseTreeDetail.Size(192, 88),
                        black,
                        ShowcaseTreeDetail.Padding(8),
                        ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
                    ),
                    expectedTree(
                        DocumentedComponent.Text,
                        listOf(ShowcaseTreeDetail.MultilineText(TextLayout.Multiline(maxLines = 4, overflow = TextOverflow.Ellipsis, lineSpacing = 2))),
                    ),
                ),
                centeredTree(
                    IntSize(216, 64),
                    expectedTree(DocumentedComponent.TextField, listOf(ShowcaseTreeDetail.Size(200, 20))),
                ),
                expectedTree(
                    DocumentedComponent.Row,
                    listOf(
                        ShowcaseTreeDetail.Size(226, 80),
                        black,
                        ShowcaseTreeDetail.Spacing(4),
                        ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                        ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                    ),
                    expectedTree(DocumentedComponent.TextArea, listOf(ShowcaseTreeDetail.Size(200, 64))),
                    expectedTree(DocumentedComponent.Scrollbar, listOf(ShowcaseTreeDetail.Size(6, 64))),
                ),
                centeredTree(
                    IntSize(166, 64),
                    expectedTree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(150, 20))),
                ),
                centeredTree(
                    IntSize(166, 36),
                    expectedTree(DocumentedComponent.Checkbox, listOf(ShowcaseTreeDetail.Size(150, 20))),
                ),
                centeredTree(
                    IntSize(166, 36),
                    expectedTree(DocumentedComponent.CycleButton, listOf(ShowcaseTreeDetail.Size(150, 20))),
                ),
                centeredTree(
                    IntSize(166, 36),
                    expectedTree(DocumentedComponent.Slider, listOf(ShowcaseTreeDetail.Size(150, 20))),
                ),
                expectedTree(
                    DocumentedComponent.Row,
                    listOf(
                        ShowcaseTreeDetail.Size(160, 64),
                        black,
                        ShowcaseTreeDetail.Spacing(1),
                        ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                        ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                    ),
                    expectedTree(DocumentedComponent.Tab, listOf(ShowcaseTreeDetail.Size(73, 20))),
                    expectedTree(DocumentedComponent.Tab, listOf(ShowcaseTreeDetail.Size(73, 20))),
                ),
                expectedTree(
                    DocumentedComponent.ScrollArea,
                    listOf(
                        ShowcaseTreeDetail.Size(120, 48),
                        black,
                        ShowcaseTreeDetail.ScrollRate(9),
                    ),
                    expectedTree(
                        DocumentedComponent.Column,
                        listOf(
                            ShowcaseTreeDetail.Size(120, 72),
                            ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                        ),
                        *Array(4) { expectedTree(DocumentedComponent.Text) },
                    ),
                ),
                expectedTree(
                    DocumentedComponent.Row,
                    listOf(ShowcaseTreeDetail.Size(94, 48), black, ShowcaseTreeDetail.Spacing(8)),
                    expectedTree(
                        DocumentedComponent.ScrollArea,
                        listOf(ShowcaseTreeDetail.Size(80, 48), ShowcaseTreeDetail.ScrollRate(9)),
                        expectedTree(
                            DocumentedComponent.Column,
                            listOf(ShowcaseTreeDetail.Size(80, 96)),
                            *Array(6) { expectedTree(DocumentedComponent.Text) },
                        ),
                    ),
                    expectedTree(DocumentedComponent.Scrollbar, listOf(ShowcaseTreeDetail.Size(6, 48))),
                ),
                expectedTree(DocumentedComponent.VirtualList, listOf(ShowcaseTreeDetail.Size(120, 48))),
                expectedTree(DocumentedComponent.SelectionList, listOf(ShowcaseTreeDetail.Size(120, 48))),
                centeredTree(
                    IntSize(64, 64),
                    expectedTree(DocumentedComponent.Image, listOf(ShowcaseTreeDetail.Size(32, 32))),
                ),
                centeredTree(
                    IntSize(96, 64),
                    expectedTree(DocumentedComponent.Canvas, listOf(ShowcaseTreeDetail.Size(64, 32))),
                ),
                centeredTree(
                    IntSize(64, 64),
                    expectedTree(
                        DocumentedComponent.Slot,
                        listOf(ShowcaseTreeDetail.SlotHighlightable(true), ShowcaseTreeDetail.Size(18, 18)),
                    ),
                ),
                centeredTree(
                    IntSize(64, 64),
                    expectedTree(DocumentedComponent.PlayerHead, listOf(ShowcaseTreeDetail.Size(24, 24))),
                ),
                centeredTree(
                    IntSize(32, 24),
                    expectedTree(DocumentedComponent.LoadingIndicator, listOf(ShowcaseTreeDetail.Size(10, 4))),
                ),
                centeredTree(
                    IntSize(116, 28),
                    expectedTree(DocumentedComponent.ProgressBar, listOf(ShowcaseTreeDetail.Size(100, 12))),
                ),
            )

        assertEquals(DocumentedComponent.entries, ShowcaseScenarioCatalog.components.map { scenario -> scenario.component })
        expected.zip(ShowcaseScenarioCatalog.components).forEach { (expectedTree, scenario) ->
            assertTreeEquals(expectedTree, scenario.tree)
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
    fun everyScenarioUsesCompiledSourcesAndCompleteScreensKeepScaleOne() {
        (listOf(ShowcaseScenarioCatalog.overview) + ShowcaseScenarioCatalog.components).forEach { scenario ->
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

    private fun expectedTree(
        component: DocumentedComponent,
        details: List<ShowcaseTreeDetail> = emptyList(),
        vararg children: ShowcaseTree,
    ): ShowcaseTree = ShowcaseTree(component, details, children.toList())

    private fun centeredTree(
        viewport: IntSize,
        child: ShowcaseTree,
    ): ShowcaseTree =
        expectedTree(
            DocumentedComponent.Stack,
            listOf(
                ShowcaseTreeDetail.Size(viewport.width, viewport.height),
                ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
            ),
            child,
        )

    private fun assertTreeEquals(
        expected: ShowcaseTree,
        actual: ShowcaseTree,
    ) {
        assertEquals(expected.component, actual.component)
        assertEquals(expected.details, actual.details)
        assertEquals(expected.children.size, actual.children.size)
        expected.children.zip(actual.children).forEach { (expectedChild, actualChild) ->
            assertTreeEquals(expectedChild, actualChild)
        }
    }

    private fun contains(
        tree: ShowcaseTree,
        component: DocumentedComponent,
    ): Boolean = tree.component == component || tree.children.any { child -> contains(child, component) }
}
