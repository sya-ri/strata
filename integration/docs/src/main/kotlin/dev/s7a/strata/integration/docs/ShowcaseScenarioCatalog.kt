package dev.s7a.strata.integration.docs

import dev.s7a.strata.geometry.DoubleOffset
import dev.s7a.strata.geometry.IntSize
import dev.s7a.strata.layout.Alignment
import dev.s7a.strata.layout.Arrangement
import dev.s7a.strata.layout.HorizontalAlignment
import dev.s7a.strata.layout.VerticalAlignment
import dev.s7a.strata.render.ArgbColor
import dev.s7a.strata.text.TextLayout
import dev.s7a.strata.text.TextOverflow

/**
 * Owns the deterministic Minecraft component catalog used by both generator launchers.
 */
internal object ShowcaseScenarioCatalog {
    /**
     * Component scenarios in the exact public API inventory order.
     */
    internal val components: List<ComponentScenario> =
        listOf(
            ComponentScenario(
                component = DocumentedComponent.Row,
                source = componentSource("MinecraftRowExample.kt", "row"),
                viewportMetadata = ShowcaseViewport(IntSize(136, 64), 1),
                tree =
                    tree(
                        DocumentedComponent.Row,
                        listOf(
                            ShowcaseTreeDetail.Size(136, 64),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Spacing(4),
                            ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                            ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.FlowRow,
                source = componentSource("MinecraftFlowRowShowcaseExample.kt", "flow-row"),
                viewportMetadata = ShowcaseViewport(IntSize(168, 60), 1),
                tree =
                    tree(
                        DocumentedComponent.FlowRow,
                        listOf(
                            ShowcaseTreeDetail.Size(168, 60),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Padding(8),
                            ShowcaseTreeDetail.FlowRowSpacing(horizontal = 4, vertical = 4),
                            ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                            ShowcaseTreeDetail.FlowRowDefaultAlignment(VerticalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(72, 20))),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(56, 20))),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(92, 20))),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(52, 20))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Column,
                source = componentSource("MinecraftColumnExample.kt", "column"),
                viewportMetadata = ShowcaseViewport(IntSize(120, 64), 1),
                tree =
                    tree(
                        DocumentedComponent.Column,
                        listOf(
                            ShowcaseTreeDetail.Size(120, 64),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Spacing(4),
                            ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                            ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(96, 20))),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(96, 20))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Stack,
                source = componentSource("MinecraftStackExample.kt", "stack"),
                viewportMetadata = ShowcaseViewport(IntSize(64, 64), 1),
                tree =
                    tree(
                        DocumentedComponent.Stack,
                        listOf(
                            ShowcaseTreeDetail.Size(64, 64),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
                        ),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(56, 20))),
                        tree(
                            DocumentedComponent.Spacer,
                            listOf(
                                ShowcaseTreeDetail.Size(10, 10),
                                ShowcaseTreeDetail.Background(ArgbColor(0xFFE53935.toInt())),
                                ShowcaseTreeDetail.StackAlign(Alignment.CenterEnd),
                            ),
                        ),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Grid,
                source = componentSource("MinecraftGridExample.kt", "grid"),
                viewportMetadata = ShowcaseViewport(IntSize(64, 64), 1),
                tree =
                    tree(
                        DocumentedComponent.Grid,
                        listOf(
                            ShowcaseTreeDetail.Size(64, 64),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.GridColumns(3),
                            ShowcaseTreeDetail.GridSpacing(horizontal = 2, vertical = 2),
                        ),
                        *Array(9) { tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(20, 20))) },
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Spacer,
                source = componentSource("MinecraftSpacerExample.kt", "spacer"),
                viewportMetadata = ShowcaseViewport(IntSize(160, 64), 1),
                tree =
                    tree(
                        DocumentedComponent.Row,
                        listOf(
                            ShowcaseTreeDetail.Size(160, 64),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                            ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                        tree(DocumentedComponent.Spacer, listOf(ShowcaseTreeDetail.Size(16, 20))),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(60, 20))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Text,
                source = componentSource("MinecraftTextExample.kt", "text"),
                viewportMetadata = ShowcaseViewport(IntSize(192, 88), 2),
                tree =
                    tree(
                        DocumentedComponent.Stack,
                        listOf(
                            ShowcaseTreeDetail.Size(192, 88),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Padding(8),
                            ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
                        ),
                        tree(
                            DocumentedComponent.Text,
                            listOf(ShowcaseTreeDetail.MultilineText(TextLayout.Multiline(maxLines = 4, overflow = TextOverflow.Ellipsis, lineSpacing = 2))),
                        ),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.TextField,
                source = componentSource("MinecraftTextFieldShowcaseExample.kt", "text-field"),
                viewportMetadata = ShowcaseViewport(IntSize(216, 64), 2),
                tree =
                    centeredCanvas(
                        IntSize(216, 64),
                        tree(DocumentedComponent.TextField, listOf(ShowcaseTreeDetail.Size(200, 20))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.TextArea,
                source = componentSource("MinecraftTextAreaShowcaseExample.kt", "text-area"),
                viewportMetadata = ShowcaseViewport(IntSize(226, 80), 2),
                tree =
                    tree(
                        DocumentedComponent.Row,
                        listOf(
                            ShowcaseTreeDetail.Size(226, 80),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Spacing(4),
                            ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                            ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                        ),
                        tree(DocumentedComponent.TextArea, listOf(ShowcaseTreeDetail.Size(200, 64))),
                        tree(DocumentedComponent.Scrollbar, listOf(ShowcaseTreeDetail.Size(6, 64))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Button,
                source = componentSource("MinecraftButtonExample.kt", "button"),
                viewportMetadata = ShowcaseViewport(IntSize(166, 64), 1),
                tree =
                    centeredCanvas(
                        IntSize(166, 64),
                        tree(DocumentedComponent.Button, listOf(ShowcaseTreeDetail.Size(150, 20))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Checkbox,
                source = componentSource("MinecraftCheckboxExample.kt", "checkbox"),
                viewportMetadata = ShowcaseViewport(IntSize(166, 36), 1),
                tree = centeredCanvas(IntSize(166, 36), tree(DocumentedComponent.Checkbox, listOf(ShowcaseTreeDetail.Size(150, 20)))),
            ),
            ComponentScenario(
                component = DocumentedComponent.CycleButton,
                source = componentSource("MinecraftCycleButtonExample.kt", "cycle-button"),
                viewportMetadata = ShowcaseViewport(IntSize(166, 36), 1),
                tree = centeredCanvas(IntSize(166, 36), tree(DocumentedComponent.CycleButton, listOf(ShowcaseTreeDetail.Size(150, 20)))),
            ),
            ComponentScenario(
                component = DocumentedComponent.Slider,
                source = componentSource("MinecraftSliderExample.kt", "slider"),
                viewportMetadata = ShowcaseViewport(IntSize(166, 36), 1),
                tree = centeredCanvas(IntSize(166, 36), tree(DocumentedComponent.Slider, listOf(ShowcaseTreeDetail.Size(150, 20)))),
            ),
            ComponentScenario(
                component = DocumentedComponent.Tab,
                source = componentSource("MinecraftTabExample.kt", "tab"),
                viewportMetadata = ShowcaseViewport(IntSize(160, 64), 1),
                tree =
                    tree(
                        DocumentedComponent.Row,
                        listOf(
                            ShowcaseTreeDetail.Size(160, 64),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Spacing(1),
                            ShowcaseTreeDetail.Arrangement(Arrangement.Center),
                            ShowcaseTreeDetail.RowDefaultAlignment(VerticalAlignment.Center),
                        ),
                        tree(DocumentedComponent.Tab, listOf(ShowcaseTreeDetail.Size(73, 20))),
                        tree(DocumentedComponent.Tab, listOf(ShowcaseTreeDetail.Size(73, 20))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.ScrollArea,
                source = componentSource("MinecraftScrollAreaExample.kt", "scroll-area"),
                viewportMetadata = ShowcaseViewport(IntSize(120, 48), 1),
                tree =
                    tree(
                        DocumentedComponent.ScrollArea,
                        listOf(
                            ShowcaseTreeDetail.Size(120, 48),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.ScrollRate(9),
                        ),
                        tree(
                            DocumentedComponent.Column,
                            listOf(
                                ShowcaseTreeDetail.Size(120, 72),
                                ShowcaseTreeDetail.ColumnDefaultAlignment(HorizontalAlignment.Center),
                            ),
                            *Array(4) { tree(DocumentedComponent.Text, emptyList()) },
                        ),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Scrollbar,
                source = componentSource("MinecraftScrollbarExample.kt", "scrollbar"),
                viewportMetadata = ShowcaseViewport(IntSize(94, 48), 1),
                tree =
                    tree(
                        DocumentedComponent.Row,
                        listOf(
                            ShowcaseTreeDetail.Size(94, 48),
                            ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                            ShowcaseTreeDetail.Spacing(8),
                        ),
                        tree(
                            DocumentedComponent.ScrollArea,
                            listOf(ShowcaseTreeDetail.Size(80, 48), ShowcaseTreeDetail.ScrollRate(9)),
                            tree(
                                DocumentedComponent.Column,
                                listOf(ShowcaseTreeDetail.Size(80, 96)),
                                *Array(6) { tree(DocumentedComponent.Text, emptyList()) },
                            ),
                        ),
                        tree(DocumentedComponent.Scrollbar, listOf(ShowcaseTreeDetail.Size(6, 48))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.VirtualList,
                source = componentSource("MinecraftVirtualListExample.kt", "virtual-list"),
                viewportMetadata = ShowcaseViewport(IntSize(120, 48), 1),
                tree = tree(DocumentedComponent.VirtualList, listOf(ShowcaseTreeDetail.Size(120, 48))),
            ),
            ComponentScenario(
                component = DocumentedComponent.SelectionList,
                source = componentSource("MinecraftSelectionListExample.kt", "selection-list"),
                viewportMetadata = ShowcaseViewport(IntSize(120, 48), 1),
                tree = tree(DocumentedComponent.SelectionList, listOf(ShowcaseTreeDetail.Size(120, 48))),
            ),
            ComponentScenario(
                component = DocumentedComponent.Image,
                source = componentSource("MinecraftImageExample.kt", "image"),
                viewportMetadata = ShowcaseViewport(IntSize(64, 64), 1),
                tree =
                    centeredCanvas(
                        IntSize(64, 64),
                        tree(DocumentedComponent.Image, listOf(ShowcaseTreeDetail.Size(32, 32))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Canvas,
                source = componentSource("MinecraftCanvasExample.kt", "canvas"),
                viewportMetadata = ShowcaseViewport(IntSize(96, 64), 1),
                tree =
                    centeredCanvas(
                        IntSize(96, 64),
                        tree(DocumentedComponent.Canvas, listOf(ShowcaseTreeDetail.Size(64, 32))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.TiledImage,
                source = componentSource("MinecraftTiledImageExample.kt", "tiled-image"),
                viewportMetadata = ShowcaseViewport(IntSize(112, 88), 1),
                tree =
                    centeredCanvas(
                        IntSize(112, 88),
                        tree(
                            DocumentedComponent.TiledImage,
                            listOf(ShowcaseTreeDetail.Size(96, 72)),
                            tree(
                                DocumentedComponent.Spacer,
                                listOf(
                                    ShowcaseTreeDetail.Size(7, 7),
                                    ShowcaseTreeDetail.Background(ArgbColor(0xFFFFFFFF.toInt())),
                                    ShowcaseTreeDetail.TiledImageContentPosition(DoubleOffset(32.0, 24.0), Alignment.Center),
                                ),
                            ),
                        ),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.Slot,
                source = componentSource("MinecraftSlotShowcaseExample.kt", "slot"),
                viewportMetadata = ShowcaseViewport(IntSize(64, 64), 1),
                tree =
                    centeredCanvas(
                        IntSize(64, 64),
                        tree(
                            DocumentedComponent.Slot,
                            listOf(ShowcaseTreeDetail.SlotHighlightable(true), ShowcaseTreeDetail.Size(18, 18)),
                        ),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.PlayerHead,
                source = componentSource("MinecraftPlayerHeadExample.kt", "player-head"),
                viewportMetadata = ShowcaseViewport(IntSize(64, 64), 1),
                tree =
                    centeredCanvas(
                        IntSize(64, 64),
                        tree(DocumentedComponent.PlayerHead, listOf(ShowcaseTreeDetail.Size(24, 24))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.LoadingIndicator,
                source = componentSource("MinecraftLoadingIndicatorExample.kt", "loading-indicator"),
                viewportMetadata = ShowcaseViewport(IntSize(32, 24), 1),
                tree =
                    centeredCanvas(
                        IntSize(32, 24),
                        tree(DocumentedComponent.LoadingIndicator, listOf(ShowcaseTreeDetail.Size(10, 4))),
                    ),
            ),
            ComponentScenario(
                component = DocumentedComponent.ProgressBar,
                source = componentSource("MinecraftProgressBarExample.kt", "progress-bar"),
                viewportMetadata = ShowcaseViewport(IntSize(116, 28), 1),
                tree =
                    centeredCanvas(
                        IntSize(116, 28),
                        tree(DocumentedComponent.ProgressBar, listOf(ShowcaseTreeDetail.Size(100, 12))),
                    ),
            ),
        )

    /**
     * Verified ConfirmScreen overview rendered before component sections.
     */
    internal val overview: OverviewScenario =
        OverviewScenario(
            source = overviewSource(),
            viewport = IntSize(320, 180),
            scale = 1,
            trees =
                listOf(
                    tree(
                        DocumentedComponent.Stack,
                        listOf(ShowcaseTreeDetail.Size(320, 180)),
                        tree(
                            DocumentedComponent.Column,
                            listOf(ShowcaseTreeDetail.Spacing(24)),
                            tree(
                                DocumentedComponent.Column,
                                listOf(ShowcaseTreeDetail.Spacing(8)),
                                tree(DocumentedComponent.Text, emptyList()),
                                tree(DocumentedComponent.Text, emptyList()),
                            ),
                            tree(
                                DocumentedComponent.Row,
                                listOf(ShowcaseTreeDetail.Spacing(4)),
                                tree(DocumentedComponent.Button, emptyList()),
                                tree(DocumentedComponent.Button, emptyList()),
                            ),
                        ),
                    ),
                ),
        )

    /**
     * Complete vanilla and Mod use cases rendered after the primitive component catalog.
     */
    internal val screens: List<ScreenScenario> =
        listOf(
            ScreenScenario(
                DocumentedScreen.SocialInteractions,
                SourceReference(
                    "integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftSocialExample.kt",
                    "social-screen",
                ),
                ShowcaseViewport(IntSize(320, 240), 1),
            ),
            ScreenScenario(
                DocumentedScreen.SynchronizedInventory,
                SourceReference(
                    "integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftInventoryExample.kt",
                    "inventory-screen",
                ),
                ShowcaseViewport(IntSize(320, 240), 1),
            ),
            ScreenScenario(
                DocumentedScreen.IndustrialController,
                industrialSource(),
                ShowcaseViewport(IntSize(320, 180), 1),
            ),
            ScreenScenario(
                DocumentedScreen.PowerMilestones,
                SourceReference(
                    "integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftProgressExample.kt",
                    "progress-screen",
                ),
                ShowcaseViewport(IntSize(320, 180), 1),
            ),
        )

    /**
     * Checks catalog order, uniqueness, and exact component coverage.
     *
     * @throws IllegalArgumentException when the typed catalog is incomplete, duplicated, or invalid.
     */
    internal fun validate() {
        val expected = DocumentedComponent.entries
        val actual = components.map { scenario -> scenario.component }
        require(actual == expected) { "Showcase catalog does not cover components in typed order." }
        require(actual.toSet().size == actual.size) { "Showcase catalog contains duplicate components." }
        val componentSourcePaths = components.map { scenario -> scenario.source.relativePath }
        require(componentSourcePaths.toSet().size == componentSourcePaths.size) {
            "Each component must own one dedicated showcase source file."
        }
        val completeScreenSourcePaths =
            (listOf(overview.source) + screens.map { scenario -> scenario.source })
                .map { source -> source.relativePath }
                .toSet()
        require(componentSourcePaths.none(completeScreenSourcePaths::contains)) {
            "A dedicated component showcase must not reuse a complete-screen source."
        }
        val sources = listOf(overview.source) + components.map { scenario -> scenario.source } + screens.map { scenario -> scenario.source }
        sources.groupBy { source -> source.slug }.forEach { (slug, references) ->
            require(references.distinctBy { reference -> reference.relativePath to reference.slug }.size == 1) {
                "Showcase source slug $slug identifies multiple regions."
            }
        }
        val outputPaths =
            listOf("components.md", "overview.png", "headless-render.properties") +
                components
                    .map { scenario -> "${scenario.component.slug}.png" }
                    .plus(screens.map { scenario -> "screen-${scenario.screen.slug}.png" })
        require(outputPaths.toSet().size == outputPaths.size) { "Showcase output paths must be unique." }
        require(0 < overview.viewport.width && 0 < overview.viewport.height && 0 < overview.scale) {
            "Overview viewport metadata must be positive."
        }
        components.forEach { scenario ->
            require(0 < scenario.viewport.width && 0 < scenario.viewport.height && 0 < scenario.scale) {
                "Component viewport metadata must be positive for ${scenario.component.apiMethodName}."
            }
        }
        require(screens.map { scenario -> scenario.screen } == DocumentedScreen.entries) {
            "Showcase screen catalog does not cover screens in typed order."
        }
        screens.forEach { scenario ->
            require(0 < scenario.viewportWidth && 0 < scenario.viewportHeight && scenario.scale == 1) {
                "Screen viewport metadata must be positive at scale one for ${scenario.screen.title}."
            }
        }
        overview.trees.forEach(::validateDetails)
        components.forEach { scenario ->
            require(containsComponent(scenario.tree, scenario.component)) {
                "Showcase tree does not contain its documented component ${scenario.component.apiMethodName}."
            }
            validateDetails(scenario.tree)
        }
    }

    private fun tree(
        component: DocumentedComponent,
        details: List<ShowcaseTreeDetail>,
        vararg children: ShowcaseTree,
    ): ShowcaseTree = ShowcaseTree(component, details, children.toList())

    private fun centeredCanvas(
        viewport: IntSize,
        child: ShowcaseTree,
    ): ShowcaseTree =
        tree(
            DocumentedComponent.Stack,
            listOf(
                ShowcaseTreeDetail.Size(viewport.width, viewport.height),
                ShowcaseTreeDetail.Background(ArgbColor(0xFF000000.toInt())),
                ShowcaseTreeDetail.StackContentAlignment(Alignment.Center),
            ),
            child,
        )

    private fun componentSource(
        fileName: String,
        slug: String,
    ): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/$fileName",
            slug,
        )

    private fun overviewSource(): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftOverviewExample.kt",
            "overview",
        )

    private fun industrialSource(): SourceReference =
        SourceReference(
            "integration/minecraft-fabric-unobfuscated/src/gametest/kotlin/dev/s7a/strata/integration/minecraft/fabric/MinecraftIndustrialExample.kt",
            "industrial-screen",
        )

    private fun validateDetails(tree: ShowcaseTree) {
        tree.details.forEach(::validateDetail)
        tree.children.forEach { child -> validateDetails(child) }
    }

    private fun containsComponent(
        tree: ShowcaseTree,
        component: DocumentedComponent,
    ): Boolean = tree.component == component || tree.children.any { child -> containsComponent(child, component) }

    private fun validateDetail(detail: ShowcaseTreeDetail) {
        when (detail) {
            ShowcaseTreeDetail.FillMaxSize,
            is ShowcaseTreeDetail.Background,
            is ShowcaseTreeDetail.RowAlign,
            is ShowcaseTreeDetail.FlowRowAlign,
            is ShowcaseTreeDetail.ColumnAlign,
            is ShowcaseTreeDetail.StackAlign,
            is ShowcaseTreeDetail.TiledImageContentPosition,
            is ShowcaseTreeDetail.GridAlign,
            is ShowcaseTreeDetail.Arrangement,
            is ShowcaseTreeDetail.RowDefaultAlignment,
            is ShowcaseTreeDetail.FlowRowDefaultAlignment,
            is ShowcaseTreeDetail.ColumnDefaultAlignment,
            is ShowcaseTreeDetail.StackContentAlignment,
            is ShowcaseTreeDetail.GridContentAlignment,
            is ShowcaseTreeDetail.MultilineText,
            -> Unit

            is ShowcaseTreeDetail.Size -> require(0 < detail.width && 0 < detail.height) { "Showcase sizes must be positive." }

            is ShowcaseTreeDetail.Height -> require(0 < detail.value) { "Showcase heights must be positive." }

            is ShowcaseTreeDetail.Padding -> require(0 <= detail.all) { "Showcase padding must be nonnegative." }

            is ShowcaseTreeDetail.Spacing -> require(0 <= detail.value) { "Showcase spacing must be nonnegative." }

            is ShowcaseTreeDetail.GridColumns -> require(0 < detail.value) { "Showcase Grid columns must be positive." }

            is ShowcaseTreeDetail.GridSpacing -> validateSpacing(detail.horizontal, detail.vertical, DocumentedComponent.Grid)

            is ShowcaseTreeDetail.FlowRowSpacing -> validateSpacing(detail.horizontal, detail.vertical, DocumentedComponent.FlowRow)

            is ShowcaseTreeDetail.ScrollRate -> require(0 < detail.value) { "Showcase Scroll rates must be positive." }

            is ShowcaseTreeDetail.SlotHighlightable -> Unit

            is ShowcaseTreeDetail.Weight -> require(detail.weight.isFinite() && 0 < detail.weight) { "Showcase weights must be positive finite values." }
        }
    }

    private fun validateSpacing(
        horizontal: Int,
        vertical: Int,
        component: DocumentedComponent,
    ) {
        require(0 <= horizontal && 0 <= vertical) { "Showcase ${component.apiMethodName} spacing must be nonnegative." }
    }
}
